#!/bin/bash
# 第193轮模拟器验证用：造一批「前台包名可控」的桩 App，
# 用来验证全局 Dock 的让位判定矩阵。
#
#   桩包名                       沉浸？   期望让位
#   ------------------------------------------------------------
#   com.netease.cloudmusic.iot   是      是（名单命中）
#   com.luna.music.car           是      是（名单命中）
#   com.stub.immersive           是      是（探测命中）
#   com.stub.plain                否      否
set -e
SDK=/Users/roy/Documents/desktopapp/toolchain/sdk
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-29/android.jar
KS=$HOME/.android/debug.keystore
OUT=/tmp/stub/out
JH=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home

build_one() {
  local pkg="$1" cls="$2" immersive="$3" label="$4"
  local work=/tmp/stub/work_$pkg
  mkdir -p "$work" "$work/src/$pkg"

  # ---- Activity 源码 ----
  cat > "$work/src/$pkg/StubActivity.java" <<EOF
package $pkg;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Color;
import android.widget.TextView;
public class StubActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (${immersive}) {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        TextView tv = new TextView(this);
        tv.setText("$label");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(28f);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setBackgroundColor(Color.parseColor("#123456"));
        setContentView(tv);
    }
}
EOF

  # ---- 清单 ----
  cat > "$work/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$pkg">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="29" />
    <application android:label="$label" android:hasCode="true" android:allowBackup="false">
        <activity android:name=".StubActivity" android:exported="true"
                  android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

  # ---- 编译 ----
  # build-tools 里没有 javac，用仓库自带的 JDK 17
  "$JH/bin/javac" -source 8 -target 8 -nowarn -cp "$AJ" \
    -d "$work/classes" "$work/src/$pkg/StubActivity.java"
  "$BT/d8" --min-api 26 --output "$work" $(find "$work/classes" -name '*.class') >/dev/null

  # ---- 打包 ----
  "$BT/aapt2" link -I "$AJ" --manifest "$work/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 29 -o "$work/base.apk" >/dev/null
  ( cd "$work" && zip -q -X base.apk classes.dex )
  "$BT/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/$pkg.apk" "$work/aligned.apk" >/dev/null
  echo "  ✔ $pkg.apk  ($label)"
}

mkdir -p "$OUT"
build_one com.netease.cloudmusic.iot StubActivity true  "网易云音乐"
build_one com.luna.music.car         StubActivity true  "汽水音乐"
build_one com.stub.immersive         StubActivity true  "沉浸型测试App"
build_one com.stub.plain              StubActivity false "普通测试App"
echo "完成 → $OUT"
