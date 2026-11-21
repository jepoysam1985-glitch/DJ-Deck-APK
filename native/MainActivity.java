package com.djdeck.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(TcpSourcePlugin.class);
    super.onCreate(savedInstanceState);
  }
}
