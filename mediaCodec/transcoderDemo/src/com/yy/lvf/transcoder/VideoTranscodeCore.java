package com.yy.lvf.transcoder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.CodecException;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaMuxer.OutputFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.yy.lvf.InputSurface;
import com.yy.lvf.OutputSurface;

/*
 * 1、使用Lock代替synchronized关键字
 * 2、多个线程访问共享变量所经历的锁路径应该是相同的
 * 3、为了避免死锁，多个线程对多个对象加锁的顺序应该相同。
 */
public class VideoTranscodeCore {
	public class OutputWrapper {
		public int			mIndex;
		public BufferInfo	mBufferInfo;

		public OutputWrapper(int index, BufferInfo bufferInfo) {
			mIndex = index;
			mBufferInfo = bufferInfo;
		}
	}

	public static final boolean				VERBOSE						= true;
	public static final String				TAG							= VideoTranscodeCore.class.getSimpleName();
	public static final int					TIMEOUT_US					= 10000;

	private File							mInputF;
	private File							mOutputF;

	private boolean							mNeedTranscodeVideo			= false;
	private boolean							mNeedTranscodeAudio			= true;

	private String							mOutputVideoMime			= "video/avc";
	private int								mOutputVideoWidth			= 360;
	private int								mOutputVideoHeight			= 360;
	private int								mOutputVideoBitRate			= 600000;
	private int								mOutputVideoFrameRate		= 10;
	private int								mOutputVideoIFrameInterval	= 5;

	private String							mOutputAudioMime			= "audio/mp4a-latm";
	private int								mOutputAudioBitRate			= 128000;
	private int								mOutputAudioChannelCount	= 2;

	private MediaExtractor					mAudioExtractor;
	private MediaExtractor					mVideoExtractor;
	private MediaCodec						mAudioDecoder;
	private MediaCodec						mVideoDecoder;
	private OutputSurface					mVideoDecoderOutputSurface;
	private MediaCodec						mAudioEncoder;
	private MediaCodec						mVideoEncoder;
	private InputSurface					mVideoEncoderInputSurface;
	private MediaMuxer						mMuxer;

	private int								mInputVideoTrack;
	private int								mInputAudioTrack;

	// 异步方式
	private MediaFormat						mAudioEncoderOutputFormat	= null;
	private int								mAudioMuxerTrack			= -1;
	private boolean							mAudioEncodeDone			= false;
	private MediaFormat						mVideoEncoderOutputFormat	= null;
	private int								mVideoMuxerTrack			= -1;
	private boolean							mVideoEncodeDone			= false;

	private boolean							mMuxerStarted				= false;

	private HandlerThread					mAudioDecodeThread;
	private HandlerThread					mAudioEncodeThread;
	private HandlerThread					mVideoDecodeThread;
	private HandlerThread					mVideoEncodeThread;

	private final Lock						mAudioDecoderQueueLock		= new ReentrantLock();
	private final Lock						mAudioEncoderQueueLock		= new ReentrantLock();
	private final Lock						mMuxerLock					= new ReentrantLock();

	private BlockingQueue<Integer>			mAudioDecoderInputQueue		= new LinkedBlockingQueue<Integer>();
	private BlockingQueue<OutputWrapper>	mAudioDecoderOutputQueue	= new LinkedBlockingQueue<OutputWrapper>();
	private BlockingQueue<Integer>			mAudioEncoderInputQueue		= new LinkedBlockingQueue<Integer>();
	private BlockingQueue<OutputWrapper>	mAudioEncoderOutputQueue	= new LinkedBlockingQueue<OutputWrapper>();

	@SuppressLint("NewApi")
	public MediaCodec.Callback				mAudioDecoderCallback		= new MediaCodec.Callback() {

																			@Override
																			public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
																				logFormat("audioDecoder", format);
																			}

																			@Override
																			public void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info) {
																				logOutput("audioDecoder", index, info);
																				try {
																					if (mAudioDecoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
																						mAudioDecoderOutputQueue.offer(new OutputWrapper(index, info));
																					} else {
																						logLock(mAudioDecoderQueueLock);
																					}
																				} catch (InterruptedException e) {
																					e.printStackTrace();
																				} finally {
																					mAudioDecoderQueueLock.unlock();
																				}
																			}

																			@Override
																			public void onInputBufferAvailable(MediaCodec codec, int index) {
																				logInput("audioDecoder", index);
																				try {
																					if (mAudioDecoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
																						mAudioDecoderInputQueue.offer(index);
																					} else {
																						logLock(mAudioDecoderQueueLock);
																					}
																				} catch (InterruptedException e) {
																					e.printStackTrace();
																				} finally {
																					mAudioDecoderQueueLock.unlock();
																				}
																			}

																			@Override
																			public void onError(MediaCodec codec, CodecException e) {
																				throw e;
																			}
																		};
	@SuppressLint("NewApi")
	private MediaCodec.Callback				mAudioEncoderCallback		= new MediaCodec.Callback() {

																			@Override
																			public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
																				logFormat("audioEncoder", format);
																				if (mAudioEncoderOutputFormat != null) {
																					if (VERBOSE) {
																						throw new RuntimeException("audioEncoder: format change twice");
																					}
																				} else {
																					mAudioEncoderOutputFormat = format;
																					startMuxer(format, true);
																				}
																			}

																			@Override
																			public void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info) {
																				logOutput("audioEncoder", index, info);
																				try {
																					if (mAudioEncoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
																						mAudioEncoderOutputQueue.offer(new OutputWrapper(index, info));
																					} else {
																						logLock(mAudioEncoderQueueLock);
																					}
																				} catch (Exception e) {
																					e.printStackTrace();
																				} finally {
																					mAudioEncoderQueueLock.unlock();
																				}
																			}

																			@Override
																			public void onInputBufferAvailable(MediaCodec codec, int index) {
																				logInput("audioEncoder", index);
																				try {
																					if (mAudioEncoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
																						mAudioEncoderInputQueue.offer(index);
																					} else {
																						logLock(mAudioEncoderQueueLock);
																					}
																				} catch (Exception e) {
																					e.printStackTrace();
																				} finally {
																					mAudioEncoderQueueLock.unlock();
																				}
																			}

																			@Override
																			public void onError(MediaCodec codec, CodecException e) {
																				throw e;
																			}
																		};
	@SuppressLint("NewApi")
	private MediaCodec.Callback				mVideoDecoderCb				= new MediaCodec.Callback() {

																			@Override
																			public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
																			}

																			@Override
																			public void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info) {
																				if (VERBOSE) {
																					Log.d(TAG, "video decoder: input index = " + index + ", flag = " + info.flags);
																				}
																				if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
																					mVideoDecoder.releaseOutputBuffer(index, false);
																				} else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
																					mVideoDecoder.releaseOutputBuffer(index, false);
																					mVideoEncoder.signalEndOfInputStream();
																				} else {
																					boolean render = info.size > 0;
																					mVideoDecoder.releaseOutputBuffer(index, render);
																					if (render) {
																						mVideoDecoderOutputSurface.awaitNewImage();
																						mVideoDecoderOutputSurface.drawImage();
																						mVideoEncoderInputSurface.setPresentationTime(info.presentationTimeUs * 1000);
																						mVideoEncoderInputSurface.swapBuffers();
																					}
																				}
																			}

																			@Override
																			public void onInputBufferAvailable(MediaCodec codec, int index) {// index推测不会返回-1
																				if (VERBOSE) {
																					Log.d(TAG, "video decoder: output index = " + index);
																				}
																				ByteBuffer input = codec.getInputBuffer(index);
																				int size = mVideoExtractor.readSampleData(input, 0);
																				if (size > 0) {
																					mVideoDecoder.queueInputBuffer(index, 0, size, mVideoExtractor.getSampleTime(), mVideoExtractor.getSampleFlags());
																				}
																				if (!mVideoExtractor.advance()) {
																					mVideoDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
																				}
																			}

																			@Override
																			public void onError(MediaCodec codec, CodecException e) {
																			}
																		};
	@SuppressLint("NewApi")
	private MediaCodec.Callback				mVideoEncoderCb				= new MediaCodec.Callback() {

																			@Override
																			public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
																				if (mVideoEncoderOutputFormat != null) {
																					throw new RuntimeException("video encoder: output video track change twice");
																				} else {
																					mVideoEncoderOutputFormat = format;
																					mVideoMuxerTrack = mMuxer.addTrack(mVideoEncoderOutputFormat);
																					if (!mMuxerStarted && (mVideoMuxerTrack != -1 || !mNeedTranscodeAudio)) {
																						mMuxer.start();
																						mMuxerStarted = true;
																					}
																				}
																			}

																			@Override
																			public void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info) {
																				if (VERBOSE) {
																					Log.d(TAG, "video encoder: output index = " + index + ", flag = " + info.flags);
																				}
																				if (mMuxerStarted) {
																					if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
																						codec.releaseOutputBuffer(index, false);
																					} else {
																						ByteBuffer output = codec.getOutputBuffer(index);
																						output.position(info.offset).limit(info.offset + info.size);
																						if (info.size > 0) {
																							mMuxer.writeSampleData(mVideoMuxerTrack, output, info);
																						}
																						if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
																							mVideoEncodeDone = true;
																						}
																						if (mAudioEncodeDone && mVideoEncodeDone) {
																							release();
																						}
																					}
																				}
																			}

																			@Override
																			public void onInputBufferAvailable(MediaCodec codec, int index) {
																				if (VERBOSE) {
																					Log.d(TAG, "video encoder: input index = " + index);
																				}
																			}

																			@Override
																			public void onError(MediaCodec codec, CodecException e) {
																			}
																		};
	private Callback						mCallback;
	private long							mAudioTrackAndVideoTrackTimeUs;										// 总时间

	public interface Callback {
		void transcoding(final int progress);// 百分比分子整数
	}

	public void setCallback(Callback cb) {
		mCallback = cb;
	}

	// private VideoTranscodeCore() {
	// }

	public void setFile(File input, File output) {
		mInputF = input;
		mOutputF = output;
	}

	private boolean syncInit() {
		if (mNeedTranscodeAudio) {
			MediaCodecInfo encoderInfo = checkCapabilities(mOutputAudioMime, true);
			if (encoderInfo == null) {
				if (VERBOSE) {
					Log.d(TAG, "codec info: 不支持" + mOutputAudioMime + "的编码");
				}
				return false;
			}
			mAudioExtractor = new MediaExtractor();
			try {
				mAudioExtractor.setDataSource(mInputF.getAbsolutePath());
				mInputAudioTrack = getTrackByMime(mAudioExtractor, mOutputAudioMime);
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (mInputAudioTrack == -1) {
				if (VERBOSE) {
					Log.d(TAG, "extractor: 未找到audio mime对应的track");
				}
				return false;
			}
			mAudioExtractor.selectTrack(mInputAudioTrack);
			String inputAudioTrackMime = mAudioExtractor.getTrackFormat(mInputAudioTrack).getString(MediaFormat.KEY_MIME);
			mAudioTrackAndVideoTrackTimeUs += mAudioExtractor.getTrackFormat(mInputAudioTrack).getLong(MediaFormat.KEY_DURATION);
			if (VERBOSE) {
				Log.d(TAG, "time us: a+v = " + mAudioTrackAndVideoTrackTimeUs);
			}
			MediaCodecInfo decoderInfo = checkCapabilities(inputAudioTrackMime, false);
			if (decoderInfo == null) {
				if (VERBOSE) {
					Log.d(TAG, "codec info: 不支持" + inputAudioTrackMime + "的解码");
				}
				return false;
			}
			try {
				mAudioDecoder = MediaCodec.createByCodecName(decoderInfo.getName());
				mAudioDecoder.configure(mAudioExtractor.getTrackFormat(mInputAudioTrack), null, null, 0);
				mAudioDecoder.start();

				MediaFormat outputAudioTrackFormat = createAudioTrackFormat(mAudioExtractor.getTrackFormat(mInputAudioTrack));
				mAudioEncoder = MediaCodec.createByCodecName(encoderInfo.getName());
				mAudioEncoder.configure(outputAudioTrackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
				mAudioEncoder.start();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (mNeedTranscodeVideo) {
			MediaCodecInfo encoderInfo = checkCapabilities(mOutputVideoMime, true);
			if (encoderInfo == null) {
				if (VERBOSE) {
					Log.d(TAG, "codec info: 不支持" + mOutputVideoMime + "的编码");
				}
				return false;
			}
			mVideoExtractor = new MediaExtractor();
			try {
				mVideoExtractor.setDataSource(mInputF.getAbsolutePath());
				mInputVideoTrack = getTrackByMime(mVideoExtractor, "video");
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (mInputVideoTrack == -1) {
				if (VERBOSE) {
					Log.d(TAG, "extractor: 未找到video track");
				}
				return false;
			}
			mVideoExtractor.selectTrack(mInputVideoTrack);
			String inputVideoTrackMime = mVideoExtractor.getTrackFormat(mInputVideoTrack).getString(MediaFormat.KEY_MIME);
			mAudioTrackAndVideoTrackTimeUs += mVideoExtractor.getTrackFormat(mInputVideoTrack).getLong(MediaFormat.KEY_DURATION);
			if (VERBOSE) {
				Log.d(TAG, "time us: a+v = " + mAudioTrackAndVideoTrackTimeUs);
			}
			MediaCodecInfo decoderInfo = checkCapabilities(inputVideoTrackMime, false);
			if (decoderInfo == null) {
				if (VERBOSE) {
					Log.d(TAG, "codec info: 不支持" + inputVideoTrackMime + "的解码");
				}
				return false;
			}
			try {
				MediaFormat outputVideoTrackFormat = createVideoTrackFormat(mVideoExtractor.getTrackFormat(mInputVideoTrack));
				AtomicReference<Surface> surfaceRef = new AtomicReference<Surface>();
				mVideoEncoder = MediaCodec.createByCodecName(encoderInfo.getName());
				mVideoEncoder.configure(outputVideoTrackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
				surfaceRef.set(mVideoEncoder.createInputSurface());
				mVideoEncoder.start();
				mVideoEncoderInputSurface = new InputSurface(surfaceRef.get());
				mVideoEncoderInputSurface.makeCurrent();

				mVideoDecoder = MediaCodec.createByCodecName(decoderInfo.getName());
				mVideoDecoderOutputSurface = new OutputSurface();
				mVideoDecoder.configure(mVideoExtractor.getTrackFormat(mInputVideoTrack), mVideoDecoderOutputSurface.getSurface(), null, 0);
				mVideoDecoder.start();
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
		}
		try {
			mMuxer = new MediaMuxer(mOutputF.getAbsolutePath(), OutputFormat.MUXER_OUTPUT_MPEG_4);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return mNeedTranscodeAudio || mNeedTranscodeVideo;
	}

	public void syncDoExtractDecodeEncodeMux() {
		boolean audioExtractDone = false;
		boolean videoExtractDone = false;

		boolean audioDecodeDone = false;
		int audioPendingEncodeIndex = -1;
		boolean videoDecodeDone = false;

		boolean audioEncodeDone = false;
		int audioEncoderDeterminedTrack = -1;
		MediaFormat audioEncoderDeterminedFormat = null;
		boolean videoEncodeDone = false;
		int videoEncoderDeterminedTrack = -1;
		MediaFormat videoEncoderDeterminedFormat = null;

		boolean muxerStarted = false;

		long preAudioAccessUnitPresentationTimestamp = 0;
		long preVideoFramePresentationTimestamp = 0;

		ByteBuffer[] audioDecoderInputBuffer = null;
		ByteBuffer[] videoDecoderInputBuffer = null;

		ByteBuffer[] audioDecoderOutputBuffer = null;
		MediaCodec.BufferInfo audioDecoderOutputBufferInfo = null;
		// video decoder使用surface render输出，所以没有对应的输出buffer。
		MediaCodec.BufferInfo videoDecoderOutputBufferInfo = null;

		ByteBuffer[] audioEncoderInputBuffer = null;
		// video encoder使用surface和video decoder的输出surface交换数据，所以没有对应的输入buffer。

		ByteBuffer[] audioEncoderOutputBuffer = null;
		MediaCodec.BufferInfo audioEncoderOutputBufferInfo = null;
		ByteBuffer[] videoEncoderOutputBuffer = null;
		MediaCodec.BufferInfo videoEncoderOutputBufferInfo = null;

		if (mNeedTranscodeAudio) {
			audioDecoderInputBuffer = mAudioDecoder.getInputBuffers();

			audioDecoderOutputBuffer = mAudioDecoder.getOutputBuffers();
			audioDecoderOutputBufferInfo = new MediaCodec.BufferInfo();

			audioEncoderInputBuffer = mAudioEncoder.getInputBuffers();

			audioEncoderOutputBuffer = mAudioEncoder.getOutputBuffers();
			audioEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
		}
		if (mNeedTranscodeVideo) {
			videoDecoderInputBuffer = mVideoDecoder.getInputBuffers();

			videoDecoderOutputBufferInfo = new MediaCodec.BufferInfo();

			videoEncoderOutputBuffer = mVideoEncoder.getOutputBuffers();
			videoEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
		}

		while ((mNeedTranscodeAudio && !audioEncodeDone) || (mNeedTranscodeVideo && !videoEncodeDone)) {
			// 音频提取--->解码输入
			if (mNeedTranscodeAudio && !audioExtractDone && (audioEncoderDeterminedFormat == null || muxerStarted)) {
				int inputBufferIndex = mAudioDecoder.dequeueInputBuffer(TIMEOUT_US);
				if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					if (VERBOSE) {
						Log.d(TAG, "audio decoder: 输入阻塞");
					}
				} else if (inputBufferIndex < 0) {
					if (VERBOSE) {
						Log.d(TAG, "audio decoder: invalid index");
					}
				} else {
					ByteBuffer input = audioDecoderInputBuffer[inputBufferIndex];
					int size = mAudioExtractor.readSampleData(input, 0);
					long timeUs = mAudioExtractor.getSampleTime();
					int flag = mAudioExtractor.getSampleFlags();
					if (VERBOSE) {
						Log.d(TAG, "audio extractor: " + "[audio decoder input index: " + inputBufferIndex + ", size: " + size + ", presentation time: " + timeUs + ",  flag: " + flag + "]");
					}
					if (size > 0) {
						mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, size, timeUs, flag);
					}
					audioExtractDone = !mAudioExtractor.advance();
					if (audioExtractDone) {
						mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					}
				}
			}
			// 视频提取--->解码输入
			if (mNeedTranscodeVideo && !videoExtractDone && (videoEncoderDeterminedFormat == null || muxerStarted)) {
				int videoDecoderInputIndex = mVideoDecoder.dequeueInputBuffer(TIMEOUT_US);
				if (videoDecoderInputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					if (VERBOSE) {
						Log.d(TAG, "video decoder: 输入阻塞");
					}
				} else if (videoDecoderInputIndex < 0) {
					if (VERBOSE) {
						Log.d(TAG, "video decoder: invalid index");
					}
				} else {
					ByteBuffer inputBuffer = videoDecoderInputBuffer[videoDecoderInputIndex];
					int size = mVideoExtractor.readSampleData(inputBuffer, 0);
					long timeUs = mVideoExtractor.getSampleTime();
					int flag = mVideoExtractor.getSampleFlags();
					if (VERBOSE) {
						Log.d(TAG, "video extractor: " + "[video decoder input index: " + videoDecoderInputIndex + ", size: " + size + ", presentation time: " + timeUs + ",  flag: " + flag + "]");
					}
					if (size > 0) {
						mVideoDecoder.queueInputBuffer(videoDecoderInputIndex, 0, size, timeUs, flag);
					}
					videoExtractDone = !mVideoExtractor.advance();
					if (videoExtractDone) {
						mVideoDecoder.queueInputBuffer(videoDecoderInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					}
				}
			}
			// 音频解码输出--->音频编码输入
			if (mNeedTranscodeAudio && !audioDecodeDone && audioPendingEncodeIndex == -1 && (audioEncoderDeterminedFormat == null || muxerStarted)) {
				int audioDecoderOutputIndex = mAudioDecoder.dequeueOutputBuffer(audioDecoderOutputBufferInfo, TIMEOUT_US);
				if (audioDecoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					if (VERBOSE) {
						Log.d(TAG, "audio decoder: 输入阻塞");
					}
				} else if (audioDecoderOutputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
					audioDecoderOutputBuffer = mAudioDecoder.getOutputBuffers();
				} else if (audioDecoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				} else if (audioDecoderOutputIndex < 0) {
					if (VERBOSE) {
						Log.d(TAG, "audio decoder: invalid output index");
					}
				} else {
					if ((audioDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						mAudioDecoder.releaseOutputBuffer(audioDecoderOutputIndex, false);
					} else if (audioDecoderOutputBufferInfo.size > 0) {
						audioPendingEncodeIndex = audioDecoderOutputIndex;
					} else {
						if (VERBOSE) {
							Log.d(TAG, "audio decoder: invalid output size");
						}
					}
				}
			}
			if (mNeedTranscodeAudio && !audioDecodeDone && audioPendingEncodeIndex != -1) {
				int inputIndex = mAudioEncoder.dequeueInputBuffer(TIMEOUT_US);
				if (inputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					if (VERBOSE) {
						Log.d(TAG, "audio encoder: 输入阻塞");
					}
				} else if (inputIndex < 0) {
					if (VERBOSE) {
						Log.d(TAG, "audio encoder: invalid input index");
					}
				} else if (inputIndex >= 0) {
					ByteBuffer encoderInputBuffer = audioEncoderInputBuffer[inputIndex];
					ByteBuffer decoderOutputBuffer = audioDecoderOutputBuffer[audioPendingEncodeIndex].duplicate();
					decoderOutputBuffer.position(audioDecoderOutputBufferInfo.offset);
					decoderOutputBuffer.limit(audioDecoderOutputBufferInfo.offset + audioDecoderOutputBufferInfo.size);
					encoderInputBuffer.position(0);
					encoderInputBuffer.put(decoderOutputBuffer);

					mAudioEncoder.queueInputBuffer(inputIndex, 0, audioDecoderOutputBufferInfo.size, audioDecoderOutputBufferInfo.presentationTimeUs, audioDecoderOutputBufferInfo.flags);
					mAudioDecoder.releaseOutputBuffer(audioPendingEncodeIndex, false);
					audioPendingEncodeIndex = -1;
					if ((audioDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						audioDecodeDone = true;
					}
				}
			}
			// 视频解码输出--->视频编码输入
			if (mNeedTranscodeVideo && !videoDecodeDone && (videoEncoderDeterminedFormat == null || muxerStarted)) {
				int decoderOutputIndex = mVideoDecoder.dequeueOutputBuffer(videoDecoderOutputBufferInfo, TIMEOUT_US);
				if (decoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					if (VERBOSE) {
						Log.d(TAG, "video encoder: 输出阻塞");
					}
				} else if (decoderOutputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

				} else if (decoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

				} else if (decoderOutputIndex < 0) {

				} else {
					// BufferInfo的几个参数应该怎么判断和处理
					if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						mVideoDecoder.releaseOutputBuffer(decoderOutputIndex, false);
					} else {
						boolean render = (videoDecoderOutputBufferInfo.size > 0);
						mVideoDecoder.releaseOutputBuffer(decoderOutputIndex, render);
						if (render) {
							mVideoDecoderOutputSurface.awaitNewImage();
							mVideoDecoderOutputSurface.drawImage();
							mVideoEncoderInputSurface.setPresentationTime(videoDecoderOutputBufferInfo.presentationTimeUs * 1000);
							mVideoEncoderInputSurface.swapBuffers();
						}
						if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
							videoDecodeDone = true;
							mVideoEncoder.signalEndOfInputStream();
						}
					}
				}
			}
			// 音频编码输出--->muxer输入
			if (mNeedTranscodeAudio && !audioEncodeDone && (audioEncoderDeterminedFormat == null || muxerStarted)) {
				int encoderOutputIndex = mAudioEncoder.dequeueOutputBuffer(audioEncoderOutputBufferInfo, TIMEOUT_US);
				if (encoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

				} else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
					audioEncoderOutputBuffer = mAudioEncoder.getOutputBuffers();
				} else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					if (audioEncoderDeterminedFormat != null) {
						throw new RuntimeException("audio encoder: output format change twice");
					}
					audioEncoderDeterminedFormat = mAudioEncoder.getOutputFormat();
					audioEncoderDeterminedTrack = mMuxer.addTrack(audioEncoderDeterminedFormat);
					if (!muxerStarted && (videoEncoderDeterminedTrack != -1 || !mNeedTranscodeVideo)) {
						mMuxer.start();
						muxerStarted = true;
					}
				} else if (encoderOutputIndex < 0) {
					if (VERBOSE) {
						Log.d(TAG, "audio encoder: invalid output index");
					}
				} else {
					if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						mAudioEncoder.releaseOutputBuffer(encoderOutputIndex, false);
					} else {
						ByteBuffer encoderOutputBuffer = audioEncoderOutputBuffer[encoderOutputIndex];
						encoderOutputBuffer.position(audioEncoderOutputBufferInfo.offset);
						encoderOutputBuffer.limit(audioEncoderOutputBufferInfo.offset + audioEncoderOutputBufferInfo.size);
						mMuxer.writeSampleData(audioEncoderDeterminedTrack, encoderOutputBuffer, audioEncoderOutputBufferInfo);
						if (mCallback != null) {
							preAudioAccessUnitPresentationTimestamp = audioEncoderOutputBufferInfo.presentationTimeUs;
							mCallback.transcoding((int) ((preAudioAccessUnitPresentationTimestamp + preVideoFramePresentationTimestamp) / (mAudioTrackAndVideoTrackTimeUs / 100)));
						}
						mAudioEncoder.releaseOutputBuffer(encoderOutputIndex, false);
						if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
							audioEncodeDone = true;
							if (mCallback != null && (!mNeedTranscodeVideo || videoEncodeDone)) {
								mCallback.transcoding(100);
							}
						}
					}
				}
			}
			// 视频编码输出--->muxer输入
			if (mNeedTranscodeVideo && !videoEncodeDone && (videoEncoderDeterminedFormat == null || muxerStarted)) {
				int encoderOutputIndex = mVideoEncoder.dequeueOutputBuffer(videoEncoderOutputBufferInfo, TIMEOUT_US);
				if (encoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

				} else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
					videoEncoderOutputBuffer = mVideoEncoder.getOutputBuffers();
				} else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					if (videoEncoderDeterminedFormat != null) {
						throw new RuntimeException("video encoder: output format change twice");
					}
					videoEncoderDeterminedFormat = mVideoEncoder.getOutputFormat();
					videoEncoderDeterminedTrack = mMuxer.addTrack(videoEncoderDeterminedFormat);
					if (!muxerStarted && (audioEncoderDeterminedTrack != -1 || !mNeedTranscodeAudio)) {
						mMuxer.start();
						muxerStarted = true;
					}
				} else if (encoderOutputIndex < 0) {

				} else {
					if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						mVideoEncoder.releaseOutputBuffer(encoderOutputIndex, false);
					} else {
						ByteBuffer encoderOutputBuffer = videoEncoderOutputBuffer[encoderOutputIndex];
						encoderOutputBuffer.position(videoEncoderOutputBufferInfo.offset).limit(videoEncoderOutputBufferInfo.offset + videoEncoderOutputBufferInfo.size);
						mMuxer.writeSampleData(videoEncoderDeterminedTrack, encoderOutputBuffer, videoEncoderOutputBufferInfo);
						if (mCallback != null) {
							preVideoFramePresentationTimestamp = videoEncoderOutputBufferInfo.presentationTimeUs;
							mCallback.transcoding((int) ((preAudioAccessUnitPresentationTimestamp + preVideoFramePresentationTimestamp) / (mAudioTrackAndVideoTrackTimeUs / 100)));
						}
						mVideoEncoder.releaseOutputBuffer(encoderOutputIndex, false);
						if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
							videoEncodeDone = true;
							if (mCallback != null && (!mNeedTranscodeAudio || audioEncodeDone)) {
								mCallback.transcoding(100);
							}
						}
					}
				}
			}
		}
	}

	public void syncTranscode() {
		if (syncInit()) {
			try {
				syncDoExtractDecodeEncodeMux();
			} finally {
				release();
			}
		}
	}

	@TargetApi(23)
	private boolean asyncInit() {
		MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
		if (mNeedTranscodeAudio) {
			mAudioExtractor = new MediaExtractor();
			try {
				mAudioExtractor.setDataSource(mInputF.getAbsolutePath());
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
			int inputAudioTrack = getTrackByMime(mAudioExtractor, "audio");
			mAudioExtractor.selectTrack(inputAudioTrack);

			mAudioDecodeThread = new HandlerThread("audio decode th", Process.THREAD_PRIORITY_BACKGROUND);
			mAudioDecodeThread.start();
			mAudioEncodeThread = new HandlerThread("audio encode th", Process.THREAD_PRIORITY_BACKGROUND);
			mAudioEncodeThread.start();

			MediaFormat inputAudioFormat = mAudioExtractor.getTrackFormat(inputAudioTrack);
			mAudioDecoder = checkCapabilities(codecList, inputAudioFormat, false);
			if (mAudioDecoder == null) {
				return false;
			}
			mAudioDecoder.setCallback(mAudioDecoderCallback, new Handler(mAudioDecodeThread.getLooper()));
			mAudioDecoder.configure(inputAudioFormat, null, null, 0);
			MediaFormat outputAudioFormat = createAudioTrackFormat(inputAudioFormat);
			mAudioEncoder = checkCapabilities(codecList, outputAudioFormat, true);
			if (mAudioEncoder == null) {
				return false;
			}
			mAudioEncoder.setCallback(mAudioEncoderCallback, new Handler(mAudioEncodeThread.getLooper()));
			mAudioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			mAudioDecoder.start();
			mAudioEncoder.start();
		}

		if (mNeedTranscodeVideo) {
			mVideoExtractor = new MediaExtractor();
			try {
				mVideoExtractor.setDataSource(mInputF.getAbsolutePath());
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
			int inputVideoTrack = getTrackByMime(mVideoExtractor, "video");
			mVideoExtractor.selectTrack(inputVideoTrack);

			mVideoDecodeThread = new HandlerThread("video decode th", Process.THREAD_PRIORITY_BACKGROUND);
			mVideoDecodeThread.start();
			mVideoEncodeThread = new HandlerThread("video encode th", Process.THREAD_PRIORITY_BACKGROUND);
			mVideoEncodeThread.start();

			MediaFormat inputVideoFormat = mVideoExtractor.getTrackFormat(inputVideoTrack);
			mVideoDecoder = checkCapabilities(codecList, inputVideoFormat, false);
			if (mVideoDecoder == null) {
				return false;
			}
			mVideoDecoder.setCallback(mVideoDecoderCb, new Handler(mVideoDecodeThread.getLooper()));
			mVideoDecoderOutputSurface = new OutputSurface();
			mVideoDecoder.configure(inputVideoFormat, mVideoDecoderOutputSurface.getSurface(), null, 0);
			mVideoDecoder.start();
			AtomicReference<Surface> surfaceRef = new AtomicReference<Surface>();
			MediaFormat outputVideoFormat = createVideoTrackFormat(inputVideoFormat);
			mVideoEncoder = checkCapabilities(codecList, outputVideoFormat, true);
			if (mVideoEncoder == null) {
				return false;
			}
			mVideoEncoder.setCallback(mVideoEncoderCb, new Handler(mVideoEncodeThread.getLooper()));
			mVideoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			surfaceRef.set(mVideoEncoder.createInputSurface());
			mVideoEncoder.start();
			mVideoEncoderInputSurface = new InputSurface(surfaceRef.get());
			mVideoEncoderInputSurface.makeCurrent();
		}
		try {
			mMuxer = new MediaMuxer(mOutputF.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	private void startMuxer(final MediaFormat format, final Boolean isAudioTrack) {
		try {
			if (mMuxerLock.tryLock(100, TimeUnit.MILLISECONDS)) {
				if (!mMuxerStarted) {
					if (isAudioTrack) {
						mAudioMuxerTrack = mMuxer.addTrack(format);
					} else {
						mVideoMuxerTrack = mMuxer.addTrack(format);
					}
					if (!mMuxerStarted && (mAudioMuxerTrack != -1 || !mNeedTranscodeAudio) && (mVideoMuxerTrack != -1 || !mNeedTranscodeVideo)) {
						mMuxer.start();
						mMuxerStarted = true;
					}
				}
			} else {
				logLock(mMuxerLock);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			mMuxerLock.unlock();
		}
	}

	@TargetApi(23)
	private void asyncDoExtractDecodeEncodeMux() {
		while ((mNeedTranscodeAudio && !mAudioEncodeDone) || (mNeedTranscodeVideo && !mVideoEncodeDone)) {
			if (mNeedTranscodeAudio) {
				// 提取-解码输入
				Integer decoderInputIndex = null;
				try {
					if (mAudioDecoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
						decoderInputIndex = mAudioDecoderInputQueue.peek();
						if (decoderInputIndex != null) {
							decoderInputIndex = mAudioDecoderInputQueue.poll();
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					mAudioDecoderQueueLock.unlock();
				}
				if (decoderInputIndex != null) {
					ByteBuffer decoderInputBuffer = mAudioDecoder.getInputBuffer(decoderInputIndex);
					int size = mAudioExtractor.readSampleData(decoderInputBuffer, 0);
					if (size != -1) {
						mAudioDecoder.queueInputBuffer(decoderInputIndex, 0, size, mAudioExtractor.getSampleTime(), mAudioExtractor.getSampleFlags());
						if (!mAudioExtractor.advance()) {
							if (VERBOSE) {
								Log.d(TAG, "audioDecoder: done");
							}
						}
					} else {
						if (VERBOSE) {
							Log.d(TAG, "audioDecoder: done");
						}
					}
				}
				// 解码输出-编码输入
				OutputWrapper decoderOutputWrapper = null;
				Integer encoderInputIndex = null;
				try {
					if (mAudioDecoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
						if (mAudioEncoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
							decoderOutputWrapper = mAudioDecoderOutputQueue.peek();
							encoderInputIndex = mAudioEncoderInputQueue.peek();
							if (decoderOutputWrapper != null && encoderInputIndex != null) {
								decoderOutputWrapper = mAudioDecoderOutputQueue.poll();
								encoderInputIndex = mAudioEncoderInputQueue.poll();
							}
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					mAudioDecoderQueueLock.unlock();
					mAudioEncoderQueueLock.unlock();
				}
				if (decoderOutputWrapper != null && encoderInputIndex != null) {
					if ((decoderOutputWrapper.mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						if (VERBOSE) {
							Log.d(TAG, "audioDecoder config frame");
						}
						mAudioDecoder.releaseOutputBuffer(decoderOutputWrapper.mIndex, false);
					} else {
						if (decoderOutputWrapper.mBufferInfo.size > 0) {
							ByteBuffer decoderOutputBuffer = mAudioDecoder.getOutputBuffer(decoderOutputWrapper.mIndex);
							decoderOutputBuffer = decoderOutputBuffer.duplicate();
							decoderOutputBuffer.position(decoderOutputWrapper.mBufferInfo.offset).limit(decoderOutputWrapper.mBufferInfo.offset + decoderOutputWrapper.mBufferInfo.size);
							ByteBuffer encoderInputBuffer = mAudioEncoder.getInputBuffer(encoderInputIndex);
							encoderInputBuffer.position(0);
							encoderInputBuffer.put(decoderOutputBuffer);
						}
						mAudioEncoder.queueInputBuffer(encoderInputIndex, decoderOutputWrapper.mBufferInfo.offset, decoderOutputWrapper.mBufferInfo.size, decoderOutputWrapper.mBufferInfo.presentationTimeUs, decoderOutputWrapper.mBufferInfo.flags);
						mAudioDecoder.releaseOutputBuffer(decoderOutputWrapper.mIndex, false);
						if ((decoderOutputWrapper.mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
							if (VERBOSE) {
								Log.d(TAG, "audioDecoder eos ");
							}
						}
					}
				}
				// 编码输出-muxer输入
				OutputWrapper encoderOutputWrapper = null;
				int audioTrack = -1;
				try {
					if (!mAudioEncodeDone && mMuxerLock.tryLock(100, TimeUnit.MILLISECONDS)) {
						audioTrack = mAudioMuxerTrack;
						if (mMuxerStarted && mAudioEncoderQueueLock.tryLock(100, TimeUnit.MILLISECONDS)) {
							encoderOutputWrapper = mAudioEncoderOutputQueue.peek();
							if (encoderOutputWrapper != null) {
								encoderOutputWrapper = mAudioEncoderOutputQueue.poll();
							}
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					mMuxerLock.unlock();
					mAudioEncoderQueueLock.unlock();
				}
				if (encoderOutputWrapper != null) {
					if ((encoderOutputWrapper.mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
						if (VERBOSE) {
							Log.d(TAG, "audioEncoder config frame");
						}
						mAudioEncoder.releaseOutputBuffer(encoderOutputWrapper.mIndex, false);
					} else {
						if (encoderOutputWrapper.mBufferInfo.size > 0) {
							ByteBuffer encoderOutputBuffer = mAudioEncoder.getOutputBuffer(encoderOutputWrapper.mIndex);
							encoderOutputBuffer = encoderOutputBuffer.duplicate();
							encoderOutputBuffer.position(encoderOutputWrapper.mBufferInfo.offset).limit(encoderOutputWrapper.mBufferInfo.offset + encoderOutputWrapper.mBufferInfo.size);
							mMuxer.writeSampleData(audioTrack, encoderOutputBuffer, encoderOutputWrapper.mBufferInfo);
						}
						mAudioEncoder.releaseOutputBuffer(encoderOutputWrapper.mIndex, false);
						if ((encoderOutputWrapper.mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
							mAudioEncodeDone = true;
							if (VERBOSE) {
								Log.d(TAG, "audioEncoder eos");
							}
						}
					}
				}
			}
		}
	}

	@TargetApi(23)
	public void asyncTranscode() {
		if (asyncInit()) {
			try {
				asyncDoExtractDecodeEncodeMux();
			} finally {
				release();
			}
		}
	}

	private void release() {
		if (mAudioExtractor != null) {
			mAudioExtractor.release();
		}
		if (mVideoExtractor != null) {
			mVideoExtractor.release();
		}
		if (mAudioDecoder != null) {
			mAudioDecoder.release();
		}
		if (mVideoDecoder != null) {
			mVideoDecoder.release();
		}
		if (mVideoDecoderOutputSurface != null) {
			mVideoDecoderOutputSurface.release();
		}
		if (mAudioEncoder != null) {
			mAudioEncoder.release();
		}
		if (mVideoEncoder != null) {
			mVideoEncoder.release();
		}
		if (mVideoEncoderInputSurface != null) {
			mVideoEncoderInputSurface.release();
		}
		if (mMuxer != null) {
			mMuxer.stop();
			mMuxer.release();
		}
		if (mAudioDecodeThread != null) {
			mAudioDecodeThread.quit();
		}
		if (mAudioEncodeThread != null) {
			mAudioEncodeThread.quit();
		}
		if (mVideoDecodeThread != null) {
			mVideoDecodeThread.quit();
		}
		if (mVideoEncodeThread != null) {
			mVideoEncodeThread.quit();
		}
	}

	private MediaFormat createVideoTrackFormat(MediaFormat inputFormat) {
		MediaFormat outputVideoTrackFormat = MediaFormat.createVideoFormat(mOutputVideoMime, mOutputVideoWidth, mOutputVideoHeight);
		outputVideoTrackFormat.setInteger(MediaFormat.KEY_BIT_RATE, mOutputVideoBitRate);
		outputVideoTrackFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mOutputVideoFrameRate);
		outputVideoTrackFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mOutputVideoIFrameInterval);
		outputVideoTrackFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		return outputVideoTrackFormat;
	}

	private MediaFormat createAudioTrackFormat(MediaFormat inputFormat) {
		MediaFormat outputAudioTrackFormat = MediaFormat.createAudioFormat(mOutputAudioMime, inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), mOutputAudioChannelCount);
		outputAudioTrackFormat.setInteger(MediaFormat.KEY_BIT_RATE, mOutputAudioBitRate);
		return outputAudioTrackFormat;
	}

	// 创建codec：首先拿到输入轨道，对应输入格式。
	public static int getTrackByMime(MediaExtractor extractor, String mime) {
		int track = -1;
		int trackCount = extractor.getTrackCount();
		for (int i = 0; i < trackCount; i++) {
			MediaFormat format = extractor.getTrackFormat(i);
			if (format != null && format.containsKey(MediaFormat.KEY_MIME) && format.getString(MediaFormat.KEY_MIME).contains(mime)) {
				track = i;
				break;
			}
			continue;
		}
		return track;
	}

	public static MediaCodecInfo checkCapabilities(String mime, boolean isEncoder) {
		for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
			MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
			String[] types = codecInfo.getSupportedTypes();
			if (types != null) {
				for (String supportedMime : types) {
					if (supportedMime != null && supportedMime.equalsIgnoreCase(mime)) {
						if (isEncoder == codecInfo.isEncoder()) {
							return codecInfo;
						}
					}
				}
			}
		}
		return null;
	}

	// api21之后创建codec的正确方法
	@TargetApi(21)
	public static MediaCodec checkCapabilities(MediaCodecList codecList, MediaFormat format, boolean isEncoder) {
		if (format == null) {
			return null;
		}
		String codecName = null;
		if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
			format.setString(MediaFormat.KEY_FRAME_RATE, null);
		}
		if (isEncoder) {
			codecName = codecList.findEncoderForFormat(format);
		} else {
			codecName = codecList.findDecoderForFormat(format);
		}
		if (TextUtils.isEmpty(codecName)) {
			return null;
		}
		try {
			return MediaCodec.createByCodecName(codecName);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static void logLock(Lock lock) {
		if (VERBOSE) {
			Log.d(TAG, Thread.currentThread() + " lock " + lock.toString() + " failed");
		}
	}

	public static void logInput(String codec, int index) {
		if (VERBOSE) {
			Log.d(TAG, codec + ": index = " + index);
		}
	}

	public static void logOutput(String codec, int index, BufferInfo info) {
		if (VERBOSE) {
			Log.d(TAG, codec + ": index = " + index + ", info = [" + info.flags + ", " + info.offset + ", " + info.presentationTimeUs + ", " + info.size + "]");
		}
	}

	public static void logFormat(String codec, MediaFormat format) {
		if (VERBOSE) {
			Log.d(TAG, codec + ": format = " + format.getString(MediaFormat.KEY_MIME));
		}
	}

}
