package com.example.boxup.bucks;

import android.content.res.Configuration;
import android.os.Build;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.util.Log;
import com.squareup.spoon.SpoonRule;
import org.junit.Rule;
import org.junit.Test;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

public final class BucksSendingTest {
  public static final String TAG = BucksSendingTest.class.getSimpleName();

  @Rule public final SpoonRule spoon = new SpoonRule();
  @Rule public final ActivityTestRule<AmountActivity> amountActivityRule =
      new ActivityTestRule<>(AmountActivity.class);

  @Test public void sendTenDollars() {
    String locale;
    Configuration configuration = InstrumentationRegistry.getTargetContext()
        .getResources()
        .getConfiguration();

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      locale = configuration.locale.getDisplayLanguage();
    } else {
      locale = configuration.getLocales().get(0).getDisplayLanguage();
    }

    Log.d(TAG, "Locale: " + locale);

    spoon.screenshot(amountActivityRule.getActivity(), "amount_empty");

    onView(withText("1")).perform(click());
    onView(withText("0")).perform(click());
    spoon.screenshot(amountActivityRule.getActivity(), "amount_ten");

    onView(withText("Send")).perform(click());
    spoon.screenshot(amountActivityRule.getActivity(), "send_clicked");
  }
}
