package org.daimhim.imc_core

import timber.multiplatform.log.Timber
import java.util.concurrent.*


/**
 * 快速超时响应队列
 */
class RapidResponseForce<T : Any>(
    private val MAX_TIMEOUT_TIME: Long = 5 * 1000,
    private val groupId: String = System.nanoTime().toString(),
) {
    companion object {
        private val MAXIMUM_IDLE_TIME = 25 * 1000L
        private var reviewThread: Thread? = null
        private val fuel  = LinkedBlockingQueue<Runnable>()
        // 等待队列
        private val waitingReaction = mutableMapOf<String, MutableMap<String, WrapOrderState<*>>>()

        //超时回调队列
        private val timeoutCallbackMap = mutableMapOf<String, ((List<*>) -> Unit)>()
        private val advancedFuel = AdvancedFuelRunnable()
        private val powerTrainRunnable = PowerTrainRunnable()
        private val syncRRF = Object()

        @Synchronized
        private fun startTrainBoiler() {
            if (reviewThread == null) {
                reviewThread = Thread(powerTrainRunnable)
                reviewThread?.start()
                return
            }
            synchronized(syncRRF){
                syncRRF.notify()
            }
        }
    }

    fun register(id: String, t: T) {
        register(id, t,MAX_TIMEOUT_TIME)
    }

    fun register(id: String, t: T, timeOut: Long) {
        Timber.i("register id:$id t:$t timeOut:$timeOut ${fuel.size}")
        fuel.offer(object : Runnable{
            override fun run() {
                val mutableMap = waitingReaction[groupId] ?: mutableMapOf()
                mutableMap.put(id, WrapOrderState(groupId = groupId, t = t, timeOut = timeOut))
                waitingReaction[groupId] = mutableMap
                Timber.i("---- register:${waitingReaction.hashCode()} ${waitingReaction.size}")
            }
        })
        startTrainBoiler()
    }

    fun unRegister(id: String,call:((T?)->Unit)? = null) {
        Timber.i("unRegister id:$id ${fuel.size}")
        fuel.offer(object : Runnable{
            override fun run() {
                val remove = waitingReaction[groupId]?.remove(id)
                if (remove != null && waitingReaction[groupId]?.isEmpty() == true) {
                    waitingReaction.remove(groupId)
                }
                call?.invoke(remove?.t as T?)
            }
        })
        startTrainBoiler()
    }
    
    fun isRegister(id: String):Boolean = waitingReaction[groupId]?.containsKey(id)?:false

    fun timeoutCallback(call: ((List<T>?) -> Unit)?) {
        Timber.i("timeoutCallback ${fuel.size}")
        fuel.offer(object : Runnable{
            override fun run() {
                if (call == null) {
                    timeoutCallbackMap.remove(groupId)
                    return
                }
                timeoutCallbackMap.put(groupId){
                    call.invoke(it as List<T>)
                }
            }
        })
        startTrainBoiler()
    }

    class WrapOrderState<T>(
        val groupId: String,
        val t: T,
        val timeOut: Long = 0L,
        var integrationTime: Long = 0L,
    )
    private class AdvancedFuelRunnable : Callable<Long> {
        // 上次时间
        var recently = -1L
        override fun call(): Long {
            // 本次最接近
            var closest = 0L
            // 上次到本次时间
            var lastInterval = 0L
            // 当前项目累计时间
            var currentItemCumulativeTime = 0L
            if (recently < 0){
                recently = System.currentTimeMillis()
            }
            // 当前时间
            val current = System.currentTimeMillis()
            // 上次与本次间隔
            lastInterval = Math.abs(current - recently)
            lastInterval = if (lastInterval < 0) 0 else lastInterval
            // 最接近超时的
            closest = 0L
            //超时队列
            val timeoutMap = mutableMapOf<String, MutableList<WrapOrderState<*>>>()
            var wrapOrderStates: MutableList<WrapOrderState<*>>
            // 遍历组
            val groupIterator = waitingReaction.iterator()
            var iterator: MutableIterator<MutableMap.MutableEntry<String, WrapOrderState<*>>>
            var groupNext: MutableMap.MutableEntry<String, MutableMap<String, WrapOrderState<*>>>
            var next: MutableMap.MutableEntry<String, WrapOrderState<*>>
            while (groupIterator.hasNext()) {
                // 遍历组内容
                groupNext = groupIterator.next()
                iterator = groupNext.value.iterator()
                while (iterator.hasNext()) {
                    next = iterator.next()
                    currentItemCumulativeTime = next.value.integrationTime + lastInterval
                    Timber.i("-----组ID ${groupNext.key}消息ID：${next.key} 累计时间：${currentItemCumulativeTime} ${lastInterval}")
                    if (currentItemCumulativeTime >= next.value.timeOut) { //超时
                        wrapOrderStates = timeoutMap[next.value.groupId] ?: mutableListOf()
                        wrapOrderStates.add(next.value)
                        timeoutMap[next.value.groupId] = wrapOrderStates
                        iterator.remove()
                        continue
                    } else if (currentItemCumulativeTime >= closest) { // 距离超时最近
                        closest = next.value.timeOut - currentItemCumulativeTime
                    }
                    // 记录本次累加
                    next.value.integrationTime = currentItemCumulativeTime
                }
                if (groupNext.value.isEmpty()) {
                    groupIterator.remove()
                }
            }
            //超时回调
            timeoutMap.forEach { entry ->
                Timber.i("----- 超时回调 ${entry.key} ${entry.value.size}")
                timeoutCallbackMap[entry.key]?.invoke(entry.value.map { it.t })
            }
            Timber.i("正在执行：${waitingReaction.hashCode()}")
            // 记录本次时间
            recently = System.currentTimeMillis()
            //等待最近的那个
            return if (closest < 0) 0L else closest
        }

    }
    private class PowerTrainRunnable : Runnable{
        override fun run() {
            var wait = 0L
            var take:Runnable?
            var isBurial = false
            var coolingTime = 0L // 用来记录 实际冷却时间
            while (true){
                if (wait <= 0L){
                    wait = MAXIMUM_IDLE_TIME
                }
                Timber.i("开始等待：$wait ${Thread.currentThread().name}")
                coolingTime = System.currentTimeMillis()
                take = fuel.poll(wait,TimeUnit.MILLISECONDS)
                coolingTime = System.currentTimeMillis() - coolingTime
                if (coolingTime < MAXIMUM_IDLE_TIME){
                    isBurial = false
                }
                Timber.i("等待结束，实际等待时间：$coolingTime ${Thread.currentThread().name}")
                Timber.i("取出fuel:${fuel.size} ${fuel.map { it.hashCode() }} ${Thread.currentThread().name}")
                take?.run()
                if (isAuxiliaryFuel()){
                    isBurial = false
                    continue
                }
                wait = advancedFuel.call()
                Timber.i("最接近的定时：${wait}")
                Timber.i("超时任务：${waitingReaction.size} ${waitingReaction.hashCode()}")
                if (isSufficientFuel()){
                    continue
                }
                advancedFuel.recently = -1L
                if (!isBurial){
                    isBurial = true
                    continue
                }
                isBurial = false
                if (!isAuxiliaryFuel() && !isSufficientFuel()){
                    break
                }
            }
            Timber.i("reviewThread = null ${Thread.currentThread().name}")
            reviewThread = null
        }

        private fun isAuxiliaryFuel():Boolean{
            return fuel.isNotEmpty()
        }
        private fun isSufficientFuel():Boolean{
            return waitingReaction.isNotEmpty()
        }
    }
}