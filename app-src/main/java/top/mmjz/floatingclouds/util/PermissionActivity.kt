package top.mmjz.floatingclouds.util

import android.app.Activity
import android.os.Bundle

/**
 * 最小化运行时权限请求中转 Activity，替代 com.lu.magic.util.permission.PermissionActivity。
 */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 暂时由 UI 层自行处理权限
        finish()
    }
}
