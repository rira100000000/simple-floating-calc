package com.example.simple_floating_calc;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1234;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // レイアウトは読み込まずに直接権限チェックとサービス起動へ
        checkOverlayPermissionAndStartService();
    }

    private void checkOverlayPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // オーバーレイ表示の権限がない場合は権限リクエスト
            Toast.makeText(this, "「画面の上に表示」の権限が必要です", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
        } else {
            // 権限がある場合はサービスを開始して自身を終了
            startFloatingCalculatorService();
            finish(); // アクティビティを終了して非表示に
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingCalculatorService();
            } else {
                Toast.makeText(this, "権限が許可されなかったため、アプリを終了します", Toast.LENGTH_SHORT).show();
            }
            // 権限の結果にかかわらずアクティビティを終了
            finish();
        }
    }

    private void startFloatingCalculatorService() {
        Intent serviceIntent = new Intent(this, FloatingCalculatorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}