package com.winlator.cmod;

import android.os.Bundle;
import com.winlator.cmod.core.TerminalSession;

public class TerminalActivity extends LocalizedActivity {
    private TerminalSession session;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_terminal);
        session = new TerminalSession(findViewById(android.R.id.content));
    }

    @Override protected void onDestroy() {
        if (session != null) session.close();
        super.onDestroy();
    }
}
