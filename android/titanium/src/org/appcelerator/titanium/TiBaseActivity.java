package org.appcelerator.titanium;

import java.lang.ref.SoftReference;
import java.lang.reflect.Array;

import org.appcelerator.kroll.KrollDict;
import org.appcelerator.titanium.proxy.ActivityProxy;
import org.appcelerator.titanium.proxy.TiWindowProxy;
import org.appcelerator.titanium.util.Log;
import org.appcelerator.titanium.util.TiActivityResultHandler;
import org.appcelerator.titanium.util.TiActivitySupport;
import org.appcelerator.titanium.util.TiActivitySupportHelper;
import org.appcelerator.titanium.util.TiConfig;
import org.appcelerator.titanium.util.TiConvert;
import org.appcelerator.titanium.util.TiUIHelper;
import org.appcelerator.titanium.view.ITiWindowHandler;
import org.appcelerator.titanium.view.TiCompositeLayout;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class TiBaseActivity extends Activity 
	implements TiActivitySupport, ITiWindowHandler
{
	private static final String TAG = "TiBaseActivity";
	private static final boolean DBG = TiConfig.LOGD;
	
	protected TiCompositeLayout layout;
	protected TiActivitySupportHelper supportHelper;
	protected TiWindowProxy window;
	protected ActivityProxy activityProxy;
	protected SoftReference<ITiMenuDispatcherListener> softMenuDispatcher;
	protected boolean mustFireInitialFocus;
	protected Handler handler;

	public TiApplication getTiApp() {
		return (TiApplication) getApplication();
	}
	
	public void setWindowProxy(TiWindowProxy proxy) {
		this.window = proxy;
		updateTitle();
		if (proxy != null) {
			// This forces orientation so that it won't change unless it's allowed
			// when using the "orientationModes" property
			int orientation = getResources().getConfiguration().orientation;
			if (proxy.isOrientationMode(orientation)) {
				setRequestedOrientation(orientation);
			} else if (proxy.getOrientationModes().length > 0) {
				setRequestedOrientation(proxy.getOrientationModes()[0]);
			}
		}
	}
	
	public void setActivityProxy(ActivityProxy proxy) {
		this.activityProxy = proxy;
	}

	public TiCompositeLayout getLayout() {
		return layout;
	}
	
	protected boolean getIntentBoolean(String property, boolean defaultValue) {
		Intent intent = getIntent();
		if (intent != null) {
			if (intent.hasExtra(property)) {
				return intent.getBooleanExtra(property, defaultValue);
			}
		}
		return defaultValue;
	}
	
	protected int getIntentInt(String property, int defaultValue) {
		Intent intent = getIntent();
		if (intent != null) {
			if (intent.hasExtra(property)) {
				return intent.getIntExtra(property, defaultValue);
			}
		}
		return defaultValue;
	}
	
	public void fireInitialFocus() {
		if (mustFireInitialFocus && window != null) {
			mustFireInitialFocus = false;
			window.fireEvent("focus", null);
		}
	}
	
	protected void updateTitle() {
		if (window == null) return;
		
		if (window.hasProperty("title")) {
			String oldTitle = (String) getTitle();
			String newTitle = TiConvert.toString(window.getProperty("title"));
			if (oldTitle == null) {
				oldTitle = "";
			}
			if (newTitle == null) {
				newTitle = "";
			}
			if (!newTitle.equals(oldTitle)) {
				final String fnewTitle = newTitle;
				runOnUiThread(new Runnable(){
					@Override
					public void run() {
						setTitle(fnewTitle);
					}
				});
			}
		}
	}
	
	// Subclasses can override to provide a custom layout
	protected TiCompositeLayout createLayout() {
		boolean vertical = getIntentBoolean("vertical", false);
		return new TiCompositeLayout(this, vertical);
	}
	
	// Subclasses can override to handle post-creation (but pre-message fire) logic
	protected void windowCreated() {
		boolean fullscreen = getIntentBoolean("fullscreen", false);
		boolean navbar = getIntentBoolean("navBarHidden", true);
		boolean modal = getIntentBoolean("modal", false);
		int softInputMode = getIntentInt("windowSoftInputMode", -1);
		boolean hasSoftInputMode = softInputMode != -1;

		if (!modal) {
			if (fullscreen) {
				getWindow().setFlags(
					WindowManager.LayoutParams.FLAG_FULLSCREEN,
					WindowManager.LayoutParams.FLAG_FULLSCREEN);
			}

			if (navbar) {
				this.requestWindowFeature(Window.FEATURE_LEFT_ICON); // TODO Keep?
				this.requestWindowFeature(Window.FEATURE_RIGHT_ICON);
				this.requestWindowFeature(Window.FEATURE_PROGRESS);
				this.requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
			} else {
				this.requestWindowFeature(Window.FEATURE_NO_TITLE);
			}
		} else {
			int flags = WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
			getWindow().setFlags(flags, flags);
		}

		if (hasSoftInputMode) {
			if (DBG) {
				Log.d(TAG, "windowSoftInputMode: " + softInputMode);
			}
			getWindow().setSoftInputMode(softInputMode);
		}
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		if (DBG) {
			Log.d(TAG, "Activity onCreate");
		}
		
		layout = createLayout();
		super.onCreate(savedInstanceState);
		windowCreated();

		if (activityProxy != null) {
			activityProxy.fireEvent("create", null);
		}
		
		setContentView(layout);
		
		handler = new Handler();

		Messenger messenger = null;
		Integer messageId = null;
		Intent intent = getIntent();
		if (intent != null) {
			if (intent.hasExtra("messenger")) {
				messenger = (Messenger) intent.getParcelableExtra("messenger");
				messageId = intent.getIntExtra("messageId", -1);
			}
		}
		
		if (messenger != null) {
			final TiBaseActivity me = this;
			final Messenger fMessenger = messenger;
			final int fMessageId = messageId;
			handler.post(new Runnable() {
				@Override
				public void run() {
					if (fMessenger != null) {
						try {
							Message msg = Message.obtain();
							msg.what = fMessageId;
							msg.obj = me;
							fMessenger.send(msg);
							if (DBG) {
								Log.d(TAG, "Notifying Window, activity is created");
							}
						} catch (RemoteException e) {
							Log.e(TAG, "Unable to message creator. finishing.");
							me.finish();
						} catch (RuntimeException e) {
							Log.e(TAG, "Unable to message creator. finishing.");
							me.finish();
						}
					}
				}
			});
		}
	}
	
	public void setMenuDispatchListener(ITiMenuDispatcherListener dispatcher) {
		softMenuDispatcher = new SoftReference<ITiMenuDispatcherListener>(
				dispatcher);
	}
	
	protected TiActivitySupportHelper getSupportHelper() {
		if (supportHelper == null) {
			this.supportHelper = new TiActivitySupportHelper(this);
		}
		return supportHelper;
	}

	// Activity Support
	public int getUniqueResultCode() {
		return getSupportHelper().getUniqueResultCode();
	}

	public void launchActivityForResult(Intent intent, int code, TiActivityResultHandler resultHandler)
	{
		getSupportHelper().launchActivityForResult(intent, code, resultHandler);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		getSupportHelper().onActivityResult(requestCode, resultCode, data);
	}

	@Override
	public void addWindow(View v, TiCompositeLayout.LayoutParams params) {
		layout.addView(v, params);
	}

	@Override
	public void removeWindow(View v) {
		layout.removeView(v);
	}
	
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) 
	{
		boolean handled = false;
		if (window == null) {
			return super.dispatchKeyEvent(event);
		}
		switch(event.getKeyCode()) {
			case KeyEvent.KEYCODE_BACK : {
				if (window.hasListeners("android:back")) {
					if (event.getAction() == KeyEvent.ACTION_UP) {
						window.fireEvent("android:back", null);
					}
					handled = true;
				}
				break;
			}
			case KeyEvent.KEYCODE_CAMERA : {
				if (window.hasListeners("android:camera")) {
					if (event.getAction() == KeyEvent.ACTION_UP) {
						window.fireEvent("android:camera", null);
					}
					handled = true;
				}
				break;
			}
			case KeyEvent.KEYCODE_FOCUS : {
				if (window.hasListeners("android:focus")) {
					if (event.getAction() == KeyEvent.ACTION_UP) {
						window.fireEvent("android:focus", null);
					}
					handled = true;
				}
				break;
			}
			case KeyEvent.KEYCODE_SEARCH : {
				if (window.hasListeners("android:search")) {
					if (event.getAction() == KeyEvent.ACTION_UP) {
						window.fireEvent("android:search", null);
					}
					handled = true;
				}
				break;
			}
			case KeyEvent.KEYCODE_VOLUME_UP : {
				if (window.hasListeners("android:volup")) {
					if (event.getAction() == KeyEvent.ACTION_UP) {
						window.fireEvent("android:volup", null);
					}
					handled = true;
				}
				break;
			}
			case KeyEvent.KEYCODE_VOLUME_DOWN : {
				if (window.hasListeners("android:voldown")) {
					if (event.getAction() == KeyEvent.ACTION_UP) {
						window.fireEvent("android:voldown", null);
					}
					handled = true;
				}
				break;
			}
		}
			
		if (!handled) {
			handled = super.dispatchKeyEvent(event);
		}
		return handled; 
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		if (softMenuDispatcher != null) {
			ITiMenuDispatcherListener dispatcher = softMenuDispatcher.get();
			if (dispatcher != null) {
				return dispatcher.dispatchHasMenu();
			}
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (softMenuDispatcher != null) {
			ITiMenuDispatcherListener dispatcher = softMenuDispatcher.get();
			if (dispatcher != null) {
				return dispatcher.dispatchMenuItemSelected(item);
			}
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (softMenuDispatcher != null) {
			ITiMenuDispatcherListener dispatcher = softMenuDispatcher.get();
			if (dispatcher != null) {
				return dispatcher.dispatchPrepareMenu(menu);
			}
		}
		return super.onPrepareOptionsMenu(menu);
	}
	
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (DBG) {
			Log.d(TAG, "configuration changed, orientation: " + newConfig.orientation + ", screenLayout: " + newConfig.screenLayout);
		}
		
		if (window != null) {
			if (window.isOrientationMode(newConfig.orientation)) {
				setRequestedOrientation(newConfig.orientation);
			}
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (DBG) {
			Log.d(TAG, "Activity onPause");
		}
		getTiApp().setWindowHandler(null);
		getTiApp().setCurrentActivity(this, null);
		if (activityProxy != null) {
			activityProxy.fireEvent("pause", null);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (DBG) {
			Log.d(TAG, "Activity onResume");
		}
		
		getTiApp().setWindowHandler(this);
		getTiApp().setCurrentActivity(this, this);
		if (activityProxy != null) {
			activityProxy.fireEvent("resume", null);
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (DBG) {
			Log.d(TAG, "Activity onStart");
		}
		updateTitle();
		
		if (window != null) {
			window.fireEvent("focus", null);
		} else {
			mustFireInitialFocus = true;
		}
		if (activityProxy != null) {
			activityProxy.fireEvent("start", null);
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (DBG) {
			Log.d(TAG, "Activity onStop");
		}
		if (window != null) {
			window.fireEvent("blur", null);
		}
		if (activityProxy != null) {
			activityProxy.fireEvent("stop", null);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (layout != null) {
			Log.e(TAG, "Layout cleanup.");
			layout.removeAllViews();
			layout = null;
		}
		
		if (window != null) {
			window.closeFromActivity();
			window = null;
		}
		if (activityProxy != null) {
			activityProxy.fireEvent("destroy", null);
			activityProxy.release();
			activityProxy = null;
		}
		handler = null;
	}

	protected boolean shouldFinishRootActivity() {
		return getIntentBoolean("finishRoot", false);
	}
	
	@Override
	public void finish()
	{
		if (window != null) {
			KrollDict data = new KrollDict();
			window.fireEvent("close", data);
		}
		
		boolean animate = getIntentBoolean("animate", true);
		if (shouldFinishRootActivity()) {
			TiApplication app = getTiApp();
			if (app != null) {
				TiRootActivity rootActivity = app.getRootActivity();
				if (rootActivity != null) {
					rootActivity.finish();
				}
			}
		}

		super.finish();
		if (!animate) {
			TiUIHelper.overridePendingTransition(this);
		}
	}
}
