package com.squareup.spoonlocale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

public class LocaleSwitcher extends BroadcastReceiver {
  public static final String TAG = LocaleSwitcher.class.getSimpleName();

  public static final String LOCALE = "locale";

  @Override public void onReceive(Context context, Intent intent) {
    String locale = intent.getStringExtra(LOCALE);
    Log.d(TAG, "starting to change locale to: " + locale);

    try {
      Class.forName("com.android.internal.app.LocalePicker")
          .getMethod("updateLocale", Locale.class)
          .invoke(null, getLocaleFromString(locale));
    } catch (IllegalAccessException e) {
        e.printStackTrace();
    } catch (InvocationTargetException e) {
        e.printStackTrace();
    } catch (NoSuchMethodException e) {
        e.printStackTrace();
    } catch (ClassNotFoundException e) {
        e.printStackTrace();
    }
  }

  private Locale getLocaleFromString(String locale) {
    String[] countryAndRegion = locale.split("-");

    if (countryAndRegion.length > 1) {
      return new Locale(countryAndRegion[0], countryAndRegion[1]);
    }

    throw new RuntimeException("");
  }
}
