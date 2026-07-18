package top.mmjz.floatingclouds.util;
import top.mmjz.floatingclouds.util.AppContext;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import top.mmjz.floatingclouds.App;

public class ClipboardUtil {
    /**
     * 复制内容到剪切板
     *
     * @param copyStr
     * @return
     */
    public static boolean copy(CharSequence copyStr) {
        try {
            //获取剪贴板管理器
            ClipboardManager cm = (ClipboardManager) AppContext.context.getSystemService(Context.CLIPBOARD_SERVICE);
            // 创建普通字符型ClipData
            ClipData mClipData = ClipData.newPlainText("Label", copyStr);
            // 将ClipData内容放到系统剪贴板里。
            cm.setPrimaryClip(mClipData);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
