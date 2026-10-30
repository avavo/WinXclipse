package com.winlator.cmod;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.core.AppLanguage;

public abstract class LocalizedActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }
}
