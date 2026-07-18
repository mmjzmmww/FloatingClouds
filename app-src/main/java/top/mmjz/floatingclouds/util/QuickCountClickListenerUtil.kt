package top.mmjz.floatingclouds.util

import android.view.View
import top.mmjz.floatingclouds.util.AppReflect
import top.mmjz.floatingclouds.util.StealthLog
class QuickCountClickListenerUtil {
    companion object {
        fun register(view: View?, fullCount: Int, maxDuration: Int, callBack: View.OnClickListener) {
            if (view == null) {
                return
            }
            val viewListener = try {
                if (view.hasOnClickListeners()) {
                    val listenerInfo = AppReflect.callMethod(view, "getListenerInfo")
                    AppReflect.getObjectField(listenerInfo, "mOnClickListener") as? View.OnClickListener
                } else {
                    null
                }
            } catch (e: Throwable) {
                null
            }
            if (viewListener is QuickCountClickListener) {
                viewListener.sourceListener
            } else {
                viewListener
            }.let {
                view.setOnClickListener(QuickCountClickListener(it, callBack, fullCount, maxDuration))
            }
        }

        fun unRegister(view: View?) {
            if (view == null) {
                return
            }
            val viewListener = try {
                if (view.hasOnClickListeners()) {
                    val listenerInfo = AppReflect.callMethod(view, "getListenerInfo")
                    AppReflect.getObjectField(listenerInfo, "mOnClickListener") as? View.OnClickListener
                } else {
                    null
                }
            } catch (e: Throwable) {
                null
            }
            if (viewListener is QuickCountClickListener) {
                view.setOnClickListener(viewListener.sourceListener)
            }

        }

    }

    class QuickCountClickListener(
        //原始点击监听器
        @JvmField var sourceListener: View.OnClickListener?,
        @JvmField var fullQuickCallBack: View.OnClickListener,
        /**点击满多少次后， 触发fullQuickCallBack */
        @JvmField var fullCount: Int = 5,
        /**最大间隔时间。当前默认150毫秒**/
        @JvmField var maxDuration: Int = 150
    ) : View.OnClickListener {
        var quickClickCount = 0
        var lastMills = 0L

        override fun onClick(v: View) {
            val currMills = System.currentTimeMillis()
            // 判断是否在有效间隔内（非首次且间隔小于maxDuration）
            val withinDuration = lastMills != 0L && (currMills - lastMills) < maxDuration
            if (withinDuration) {
                quickClickCount++
            } else {
                // 首次点击或超时，当前点击作为新序列的第一击
                if (lastMills != 0L) {
                    StealthLog.i("QuickClick: timeout, resetting count (interval=${currMills - lastMills}ms > ${maxDuration}ms)")
                }
                quickClickCount = 1
            }
            lastMills = currMills

            if (quickClickCount >= fullCount) {
                quickClickCount = 0
                lastMills = 0
                // 快速点击达到设定次数，触发回调
                StealthLog.i("QuickClick: threshold reached ($fullCount clicks), triggering callback")
                fullQuickCallBack.onClick(v)
            }
            sourceListener?.onClick(v)
        }

    }

}