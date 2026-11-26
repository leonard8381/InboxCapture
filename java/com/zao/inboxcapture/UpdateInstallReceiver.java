package com.zao.inboxcapture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent result) {
        if (!LocalUpdateInstaller.ACTION_INSTALL_RESULT.equals(result.getAction())) return;
        int sessionId = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? result.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                LocalUpdateInstaller.confirmInstall(confirmation);
                return;
            }
        }
        LocalUpdateInstaller.finishInstall(sessionId);

        String message;
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                message = "灵感已更新";
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                message = "更新已取消";
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                message = "系统已阻止安装，请检查灵感的安装权限";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                message = "APK 无效，请确认文件已完整同步";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                message = "APK 与已安装的灵感版本冲突";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                message = "存储空间不足，无法安装更新";
                break;
            default:
                message = "系统未能安装更新，请检查 APK 后重试";
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
