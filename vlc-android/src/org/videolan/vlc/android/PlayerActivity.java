package org.videolan.vlc.android;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.SurfaceHolder.Callback;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.SeekBar.OnSeekBarChangeListener;

public class PlayerActivity extends Activity {

	public final static String TAG = "VLC_Activity";

	private SurfaceView mSurface;
	private SurfaceHolder mSurfaceHolder;
	private LibVLC mLibVLC;
	private Context mContext;
	
	/** Overlay */
	private LinearLayout mOverlay;
	private LinearLayout mDecor;
	private View mSpacer;
	private static final int OVERLAY_TIMEOUT = 4000;
	private static final int FADE_OUT = 1;
	private static final int SHOW_PROGRESS = 2;
	private static final int SURFACE_SIZE = 3;
	private boolean mDragging;
	private boolean mShowing;
	private SeekBar mSeekbar;
	private TextView mTime;
	private TextView mLength;
	private ImageButton mPause;
	private ImageButton mLock;
	
	// size of the video
	private int mHeight;
	private int mWidth;

	// stop screen from dimming
	private WakeLock mWakeLock;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.player);
		mContext = MediaLibraryActivity.getInstance();
		
		// stop screen from dimming
		PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG);
		

		
		/** initialize Views an their Events */
		mDecor = (LinearLayout)findViewById(R.id.player_overlay_decor);
		mSpacer = (View)findViewById(R.id.player_overlay_spacer);
		mSpacer.setOnTouchListener(mTouchListener);
		
		LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
				Context.LAYOUT_INFLATER_SERVICE);
		mOverlay = (LinearLayout)inflater.inflate(R.layout.player_overlay, null);
		
		mTime = (TextView) mOverlay.findViewById(R.id.player_overlay_time);
		mLength = (TextView) mOverlay.findViewById(R.id.player_overlay_length);
		
		mPause = (ImageButton) mOverlay.findViewById(R.id.player_overlay_play);
		mPause.setOnClickListener(mPauseListener);
		
		mLock = (ImageButton) mOverlay.findViewById(R.id.player_overlay_lock);
		mLock.setOnClickListener(mLockListener);
		
		mSurface = (SurfaceView) findViewById(R.id.player_surface);
		mSurfaceHolder = mSurface.getHolder();	
		mSurfaceHolder.setKeepScreenOn(true);
		mSurfaceHolder.setFormat(PixelFormat.RGBX_8888);
		mSurfaceHolder.addCallback(mSurfaceCallback);

		mSeekbar = (SeekBar)mOverlay.findViewById(R.id.player_overlay_seekbar);	
		mSeekbar.setOnSeekBarChangeListener(mSeekListener);

        try {
			mLibVLC = LibVLC.getInstance();
		} catch (LibVlcException e) {
			e.printStackTrace();
		}		

		if (mLibVLC != null) {
			EventManager em = new EventManager(eventHandler);
			mLibVLC.setEventManager(em);
		}
		
		/* debug */
//		lockScreen();

	}
	
	
	@Override
	protected void onStart() {
		super.onStart();
		// load and start the selected movie
		load();
	}
	
	
	@Override
	protected void onPause() {
		stop();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		if (mLibVLC != null)
			mLibVLC.detachEventManager();
		super.onDestroy();
	}

	@Override
	public boolean onTrackballEvent(MotionEvent event) {
		showOverlay();
		return true;
	}
	
	
	
    @Override
	public void onConfigurationChanged(Configuration newConfig) {
		setSurfaceSize(mWidth, mHeight);
		super.onConfigurationChanged(newConfig);
	}


	public void setSurfaceSize(int width, int height) {
		// store video size
		mHeight = height;
		mWidth = width;	
		Message msg = mHandler.obtainMessage(SURFACE_SIZE);
		msg.arg1 = width;
		msg.arg2 = height;
		mHandler.sendMessage(msg);
    }
	
	/**
	 * Lock screen rotation
	 */
	private void lockScreen() {
		WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
		Display display = wm.getDefaultDisplay();
		
		switch (display.getRotation()) {
		case Surface.ROTATION_0:
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
			break;
		case Surface.ROTATION_90:
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
			break;
		case Surface.ROTATION_270:
			// FIXME: API Level 9+ (not tested on a device with API Level < 9)
			setRequestedOrientation(8); // SCREEN_ORIENTATION_REVERSE_LANDSCAPE
			break;
		}
	}
	
	/**
	 * Remove screen lock
	 */
	private void unlockScreen() {
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
	}
	
	
	
    /**
     *  Handle libvlc asynchronous events 
     */
    private Handler eventHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.getData().getInt("event")) {
                case EventManager.MediaPlayerPlaying:
                    Log.e(TAG, "MediaPlayerPlaying");
                    break;
                case EventManager.MediaPlayerPaused:
                    Log.e(TAG, "MediaPlayerPaused");
                    break;
                case EventManager.MediaPlayerStopped:
                    Log.e(TAG, "MediaPlayerStopped");
                    break;
                case EventManager.MediaPlayerEndReached:
                    Log.e(TAG, "MediaPlayerEndReached");
                    break;
                default:
                    Log.e(TAG, "Event not handled");
                    break;
            }
            updateOverlayPausePlay();
        }
    };
	
	/**
	 * Handle resize of the surface and the overlay
	 */
	private Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case FADE_OUT:
					hideOverlay();
					break;
				case SHOW_PROGRESS:
					int pos = setOverlayProgress();
					if (!mDragging && mShowing && mLibVLC.isPlaying()) {
						msg = obtainMessage(SHOW_PROGRESS);
						sendMessageDelayed(msg, 1000 - (pos % 1000));
					}
					break;
				case SURFACE_SIZE:
					//video size
					int vw = msg.arg1;
					int vh = msg.arg2;
					float ar = (float)vw / (float)vh;
					//screen size
					int dw = getWindowManager().getDefaultDisplay().getWidth();
					int dh = getWindowManager().getDefaultDisplay().getHeight();

					//fix ar
					if (vw > vh)
						dh = (int)(dw / ar);
					else
						dw = (int)(dh * ar);

					Log.i(TAG, "video size changed to "+vw+"x"+vh+", displaying at "+dw+"x"+dh);
					mSurfaceHolder.setFixedSize(vw, vh);
					LayoutParams lp = mSurface.getLayoutParams();
					lp.width = dw;
					lp.height = dh;
					mSurface.setLayoutParams(lp);
					mSurface.invalidate();
			}
		}
	};

	/**
	 * show/hide the overlay
	 */
    private OnTouchListener mTouchListener = new OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (!mShowing) {
                    showOverlay();
                } else {
                	hideOverlay();
                }
            }
            return false;
        }
    };
    
    
    /**
     * handle changes of the seekbar (slicer)
     */
    private OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
    	
		public void onStartTrackingTouch(SeekBar seekBar) {
			mDragging = true;
			showOverlay(3600000);
		}
		
		public void onStopTrackingTouch(SeekBar seekBar) {
			mDragging = false;
			showOverlay();
		}
		
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (fromUser) {
				mLibVLC.setTime(progress);
				setOverlayProgress();	
				mTime.setText(Util.millisToString(progress));
			}
			
		}
	};
	
	
	/**
	 * 
	 */
	private OnClickListener mPauseListener = new OnClickListener() {		
		public void onClick(View v) {
			doPausePlay();
			showOverlay();
		}
	};
	
	/**
	 * 
	 */
	private OnClickListener mLockListener = new OnClickListener() {		
		boolean isLocked = false;
		public void onClick(View v) {
			if (isLocked) {
				unlockScreen();
				isLocked = false;
			} else {
				lockScreen();
				isLocked = true;
			}
		}
	};


	
	/**
	 * attach and disattach surface to the lib
	 */
	private SurfaceHolder.Callback mSurfaceCallback = new Callback() {		
		public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			mLibVLC.attachSurface(holder.getSurface(), PlayerActivity.this, width, height);
		}

		public void surfaceCreated(SurfaceHolder holder) { }

		public void surfaceDestroyed(SurfaceHolder holder) {
			mLibVLC.detachSurface();
		}
	};

	
	/**
	 * show overlay the the default timeout
	 */
	private void showOverlay() {
		showOverlay(OVERLAY_TIMEOUT);
	}
	
	
	/**
	 * show overlay
	 */
	private void showOverlay(int timeout) {
		mHandler.sendEmptyMessage(SHOW_PROGRESS);
		if (!mShowing) {
			mShowing = true;
			mDecor.addView(mOverlay);
		}
		Message msg = mHandler.obtainMessage(FADE_OUT);
        if (timeout != 0) {
            mHandler.removeMessages(FADE_OUT);
            mHandler.sendMessageDelayed(msg, timeout);
        }
        updateOverlayPausePlay();
	}
	
	
	/**
	 * hider overlay
	 */
	private void hideOverlay() {
		if (mShowing) {
			mHandler.removeMessages(SHOW_PROGRESS);
        	Log.i(TAG, "remove View!");
			mDecor.removeView(mOverlay);
			mShowing = false;
		}
	}

	
	private void updateOverlayPausePlay() {
		if (mLibVLC == null) {
			return;
		}
		
		if (mLibVLC.isPlaying()) {
			mPause.setBackgroundResource(R.drawable.ic_pause);
		} else {
			mPause.setBackgroundResource(R.drawable.ic_play);
		}
	}
	
	
	/**
	 * play or pause the media
	 */
	private void doPausePlay() {
		if (mLibVLC.isPlaying()) {
			pause();
		} else {
			play();
		}
	}
	
	
	/**
	 * update the overlay
	 */
	private int setOverlayProgress() {
		if (mLibVLC == null) {
			return 0;
		}
		int time = (int)mLibVLC.getTime();
		int length = (int)mLibVLC.getLength();
		// Update all view elements

		mSeekbar.setMax(length);
		mSeekbar.setProgress(time);
		mTime.setText(Util.millisToString(time));
		mLength.setText(Util.millisToString(length));
		return time;
	}

	/**
	 * 
	 */
	private void play() {
		mLibVLC.play();
		mWakeLock.acquire();
	}
	
	
	/**
	 * 
	 */
	private void stop() {
		mLibVLC.stop();
		if(mWakeLock.isHeld())
			mWakeLock.release();
	}
	
	
	/**
	 * 
	 */
	private void pause() {
		mLibVLC.pause();
		mWakeLock.release();
	}
	
	
	/**
	 * 
	 */
	private void load() {
		mLibVLC.readMedia(getIntent().getExtras().getString("filePath"));
		mWakeLock.acquire();
	}
}