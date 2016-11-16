package com.yy.lvf.camera;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

import com.yy.lvf.CameraUtil;
import com.yy.lvf.LLog;
import com.yy.lvf.myegl.EglCore;
import com.yy.lvf.myegl.WindowSurface;
import com.yy.lvf.mygles.Drawable2d;
import com.yy.lvf.mygles.GlesUtil;
import com.yy.lvf.mygles.ScaledDrawable2d;
import com.yy.lvf.mygles.Sprite2d;
import com.yy.lvf.mygles.Texture2dProgram;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.TextView;

public class CameraActivity2 extends Activity implements Callback {
	public static class RenderThread extends Thread implements OnFrameAvailableListener {
		public static final int			DESIRE_PREVIEW_WIDTH		= 720;
		public static final int			DESIRE_PREVIEW_HEIGHT		= 1080;
		public static final int			DESIRE_CAMERA_FPS			= 30;

		private Object					mStartLock					= new Object();
		private boolean					mReady						= false;
		private MainHandler				mMainHandler;
		private RenderHandler			mRenderHandler;

		private int						mCameraId;
		private Camera					mCamera;

		private EglCore					mEglCore;
		private WindowSurface			mWindowSurface;
		private int						mWindowSurfaceWidth;
		private int						mWindowSurfaceHeight;
		private int						mCameraPreviewWidth;
		private int						mCameraPreviewHeight;

		private int						mZoomPercent				= 0;
		private int						mSizePercent				= 50;
		private int						mRotatePercent				= 0;

		private Texture2dProgram		mTexProgram;
		private final ScaledDrawable2d	mRectDrawable				= new ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE);
		private final Sprite2d			mRect						= new Sprite2d(mRectDrawable);
		private boolean					mOwnCamera;
		private int						mTextureId;
		private SurfaceTexture			mCameraTexture;
		private float[]					mDisplayProjectionMatrix	= new float[16];
		private float					mPosX, mPosY;

		public RenderThread(MainHandler mainHandler, boolean ownCamera) {
			mMainHandler = mainHandler;
			mOwnCamera = ownCamera;
		}

		@Override
		public void run() {
			super.run();
			Looper.prepare();
			mRenderHandler = new RenderHandler(this);
			synchronized (mStartLock) {
				mReady = true;
				mStartLock.notify();
			}
			mEglCore = new EglCore(null, 0);
			if (mOwnCamera) {
				openCamera();
			}

			Looper.loop();

			releaseGl();
			mEglCore.release();
		}

		public void waitUtilReady() throws InterruptedException {
			synchronized (mStartLock) {
				while (!mReady) {
					mStartLock.wait();
				}
			}
		}

		private void openCamera() {
			CameraUtil.CameraInstanceAndId cameraInstanceAndId = CameraUtil.openCamera(CameraInfo.CAMERA_FACING_BACK);
			if (cameraInstanceAndId == null) {
				throw new RuntimeException("unable to open camera");
			}
			mCamera = cameraInstanceAndId.mCamera;
			mCameraId = cameraInstanceAndId.mCameraId;
			CameraInfo info = new CameraInfo();
			Camera.getCameraInfo(cameraInstanceAndId.mCameraId, info);
			Parameters parameters = cameraInstanceAndId.mCamera.getParameters();
			List<Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
			if (mMainHandler.getActivity() == null) {
				throw new NullPointerException("activity from main handler is null");
			}
			int displayOrientation = CameraUtil.selectDisplayOrientation(CameraInfo.CAMERA_FACING_BACK, info.orientation, mMainHandler.getActivity().getWindowManager().getDefaultDisplay().getOrientation());
			Size desiredSize = CameraUtil.selectPreviewSize(DESIRE_PREVIEW_WIDTH, DESIRE_PREVIEW_HEIGHT, supportedPreviewSizes, displayOrientation);
			mCameraPreviewWidth = desiredSize.width;
			mCameraPreviewHeight = desiredSize.height;
			int desiredFps = CameraUtil.selectFixedFps(parameters, DESIRE_CAMERA_FPS);
			parameters.setPreviewSize(desiredSize.width, desiredSize.height);
			mCamera.setParameters(parameters);
			mCamera.setDisplayOrientation(displayOrientation);

			mMainHandler.updateUi(desiredSize, desiredFps);
		}

		@Override
		public void onFrameAvailable(SurfaceTexture surfaceTexture) {
			mMainHandler.onFrameAvaliable(surfaceTexture);
		}

		public RenderHandler getRenderHandler() {
			return mRenderHandler;
		}

		public void surfaceCreated(SurfaceHolder surfaceHolder, boolean newSurface) {
			Surface surface = surfaceHolder.getSurface();
			mWindowSurface = new WindowSurface(mEglCore, surface, false);
			mWindowSurface.makeCurrent();

			mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT_BW);
			mTextureId = mTexProgram.createTextureObject();
			if (mOwnCamera) {
				mCameraTexture = new SurfaceTexture(mTextureId);
				mCameraTexture.detachFromGLContext();
				mCameraTexture.setOnFrameAvailableListener(this);
			}
			mRect.setTexture(mTextureId);
			if (!newSurface) {
				mWindowSurfaceWidth = mWindowSurface.getWidth();
				mWindowSurfaceHeight = mWindowSurface.getHeight();
				finishSurfaceSetup();
			}
		}

		public void surfaceChanged(int width, int height) {
			LLog.d(TAG, "msgSurfaceChanged(" + width + ", " + height + ")");

			mWindowSurfaceWidth = width;
			mWindowSurfaceHeight = height;
			finishSurfaceSetup();
		}

		private void finishSurfaceSetup() {
			int width = mWindowSurfaceWidth;
			int height = mWindowSurfaceHeight;
			LLog.d(TAG, "finishSurfaceSetup size=" + width + "x" + height + " camera=" + mCameraPreviewWidth + "x" + mCameraPreviewHeight);

			// 正交投影
			Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);
			GLES20.glViewport(0, 0, width, height);

			// Default position is center of screen.
			mPosX = width / 2.0f;
			mPosY = height / 2.0f;

			updateGeometry();

			// Ready to go, start the camera.
			if (mOwnCamera) {
				LLog.d(TAG, "starting camera preview");
				try {
					mCamera.setPreviewTexture(mCameraTexture);
				} catch (IOException ioe) {
					throw new RuntimeException(ioe);
				}
				mCamera.startPreview();
			}
		}

		private void updateGeometry() {
			int width = mWindowSurfaceWidth;
			int height = mWindowSurfaceHeight;

			int smallDim = Math.min(width, height);
			// Max scale is a bit larger than the screen, so we can show over-size.
			float scaled = smallDim * (mSizePercent / 100.0f) * 1.25f;
			float cameraAspect = (float) mCameraPreviewWidth / mCameraPreviewHeight;
			int newWidth = Math.round(scaled * cameraAspect);
			int newHeight = Math.round(scaled);

			float zoomFactor = 1.0f - (mZoomPercent / 100.0f);
			int rotAngle = Math.round(360 * (mRotatePercent / 100.0f));

			mRect.setScale(newWidth, newHeight);
			mRect.setPosition(mPosX, mPosY);
			mRect.setRotation(rotAngle);
			mRectDrawable.setScale(zoomFactor);
		}

		public void onFrameAvaliable(SurfaceTexture st) {
			synchronized (TAG) {
				LLog.d(TAG, Thread.currentThread() + " rendering");
				if (!mOwnCamera) {
					mCameraTexture = st;
				}
				mCameraTexture.attachToGLContext(mTextureId);
				mCameraTexture.updateTexImage();
				draw();
				mCameraTexture.detachFromGLContext();
			}
		}

		private void draw() {
			GlesUtil.checkError("draw start");

			GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
			mRect.draw(mTexProgram, mDisplayProjectionMatrix);
			mWindowSurface.swapBuffers();

			GlesUtil.checkError("draw done");
		}

		private void releaseGl() {
			GlesUtil.checkError("releaseGl start");

			if (mWindowSurface != null) {
				mWindowSurface.release();
				mWindowSurface = null;
			}
			if (mTexProgram != null) {
				mTexProgram.release();
				mTexProgram = null;
			}
			GlesUtil.checkError("releaseGl done");

			mEglCore.makeNothingCurrent();
		}
	}

	public static class RenderHandler extends Handler {
		public static final int				MSG_SURFACE_CREATED	= 0;
		public static final int				MSG_FRAME_AVALIABLE	= 1;
		public static final int				MSG_SURFACE_CHANGED	= 2;
		private WeakReference<RenderThread>	mRenderThread;

		public RenderHandler(RenderThread thread) {
			mRenderThread = new WeakReference<CameraActivity2.RenderThread>(thread);
		}

		@Override
		public void handleMessage(Message msg) {
			RenderThread renderThread = mRenderThread.get();
			if (renderThread == null) {
				removeCallbacksAndMessages(null);
				return;
			}
			switch (msg.what) {
			case MSG_SURFACE_CREATED:
				mRenderThread.get().surfaceCreated((SurfaceHolder) msg.obj, msg.arg1 == 0 ? false : true);
				break;
			case MSG_FRAME_AVALIABLE:
				mRenderThread.get().onFrameAvaliable((SurfaceTexture) msg.obj);
				break;
			case MSG_SURFACE_CHANGED:
				mRenderThread.get().surfaceChanged(msg.arg1, msg.arg2);
				break;
			default:
				break;
			}
		}

		public void surfaceCreated(SurfaceHolder surfaceHolder, boolean newSurface) {
			Message msg = obtainMessage(MSG_SURFACE_CREATED, newSurface ? 1 : 0, 0, surfaceHolder);
			sendMessage(msg);
		}

		public void surfaceChanged(int width, int height) {
			Message msg = obtainMessage(MSG_SURFACE_CHANGED, width, height);
			sendMessage(msg);
		}

		public void onFrameAvailable(SurfaceTexture st) {
			Message msg = obtainMessage(MSG_FRAME_AVALIABLE, st);
			sendMessage(msg);
		}
	}

	public static class MainHandler extends Handler {
		public static final int					MSG_UPDATE_UI		= 0;
		public static final int					MSG_FRAME_AVALIABLE	= 1;
		private WeakReference<CameraActivity2>	mContext;

		public MainHandler(CameraActivity2 activity) {
			mContext = new WeakReference<CameraActivity2>(activity);
		}

		public CameraActivity2 getActivity() {
			return mContext.get();
		}

		@Override
		public void handleMessage(Message msg) {
			if (mContext.get() == null) {
				removeCallbacksAndMessages(null);
				return;
			}
			switch (msg.what) {
			case MSG_UPDATE_UI:
				mContext.get().updateUi((Size) msg.obj, msg.arg1);
				break;
			case MSG_FRAME_AVALIABLE:
				mContext.get().mRenderThread.getRenderHandler().onFrameAvailable((SurfaceTexture) msg.obj);
				mContext.get().mRenderThread1.getRenderHandler().onFrameAvailable((SurfaceTexture) msg.obj);
				break;
			default:
				break;
			}
		}

		public void updateUi(Size previewSize, int fps) {
			Message msg = obtainMessage(MSG_UPDATE_UI, fps, 0, previewSize);
			sendMessage(msg);
		}

		public void onFrameAvaliable(SurfaceTexture st) {
			Message msg = obtainMessage(MSG_FRAME_AVALIABLE, st);
			sendMessage(msg);
		}
	}

	public static final String	TAG	= CameraActivity2.class.getSimpleName();
	private SurfaceView			mPreviewSv;
	private SurfaceView			mPreviewSv1;

	private MainHandler			mMainHandler;
	private RenderThread		mRenderThread;
	private RenderThread		mRenderThread1;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.camera_activity2);

		mPreviewSv = (SurfaceView) findViewById(R.id.preview_sv0);
		mPreviewSv1 = (SurfaceView) findViewById(R.id.preview_sv1);

		mPreviewSv.getHolder().addCallback(this);
		mPreviewSv1.getHolder().addCallback(this);

		mMainHandler = new MainHandler(this);
	}

	@Override
	protected void onResume() {
		super.onResume();
		mRenderThread = new RenderThread(mMainHandler, true);
		mRenderThread.start();
		try {
			mRenderThread.waitUtilReady();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		mRenderThread1 = new RenderThread(mMainHandler, false);
		mRenderThread1.start();
		try {
			mRenderThread1.waitUtilReady();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		LLog.d(TAG, "surfaceCreated(" + holder + ", " + Thread.currentThread() + ")");
		if (holder == mPreviewSv.getHolder()) {
			mRenderThread.getRenderHandler().surfaceCreated(holder, true);
		} else {
			mRenderThread1.getRenderHandler().surfaceCreated(holder, true);
		}
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		LLog.d(TAG, "surfaceChanged(" + holder + ", " + format + ", " + width + ", " + height + ")");
		if (holder == mPreviewSv.getHolder()) {
			mRenderThread.getRenderHandler().surfaceChanged(width, height);
		} else {
			mRenderThread1.getRenderHandler().surfaceChanged(width, height);
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		LLog.d(TAG, "surfaceDestroyed(" + holder + ")");
	}

	public void updateUi(Size previewSize, int fps) {
		LLog.d(TAG, "updateUi(previewSize:[" + previewSize.width + ", " + previewSize.height + "]" + ", " + fps + ")");
		LayoutParams lp = mPreviewSv.getLayoutParams();
		lp.width = previewSize.width;
		lp.height = previewSize.height;
		mPreviewSv.setLayoutParams(lp);
		//		mPreviewInfoTv.setText("Fps : " + fps);
	}

}
