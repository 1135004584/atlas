package com.taobao.demo;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.widget.Toast;
import com.google.android.play.core.splitcompat.SplitCompat;

import java.io.File;

/**
 * Created by guanjie on 2017/3/16.
 */

public class DemoApplication extends Application {

    @Override
    public void onCreate() {

        super.onCreate();


    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        SplitCompat.install(this);

    }
}
