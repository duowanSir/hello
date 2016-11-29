package com.yy.lvf.player.demo;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ycloud.playersdk.BasePlayer;
import com.ycloud.playersdk.YYTexTurePlayer;
import com.yy.lvf.LLog;
import com.yy.lvf.R;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by slowergun on 2016/11/28.
 */
public class YYPlayerListAdapter extends BaseAdapter implements AbsListView.OnScrollListener,
        AbsListView.RecyclerListener {
    public static class Holder {
        public int             mPosition;
        public View            mItemView;// 用于计算在整个列表中的位置
        public View            mWrapView;// 用于计算播放Surface在item中的位置
        public TextView        mInfoTv;
        public YYTexTurePlayer mYyTexturePlayer;
    }

    public static class MainHandler extends Handler {
        public static final int MSG_PLAY                  = 0;
        public static final int MSG_RELEASE               = 1;
        public static final int MSG_ITEM_POSITION_CHANGED = 2;

        private final String TAG      = MainHandler.class.getSimpleName();
        private       int    mCurrent = -1;
        private       int    mNext    = -1;
        private YYPlayerMessageListener mPlayerListener;
        private Map<Integer, Float>     posMapPercentage;
        private Map<Integer, Holder>    posMapHolder;

        private WeakReference<YYPlayerListAdapter> mAdapter;

        public MainHandler(YYPlayerListAdapter adapter) {
            super(Looper.getMainLooper());
            mAdapter = new WeakReference<>(adapter);
            mPlayerListener = new YYPlayerMessageListener(this);
            posMapPercentage = new LinkedHashMap<>();
            posMapHolder = new HashMap<>();
        }

        @Override
        public void handleMessage(Message msg) {
            if (mAdapter.get() == null) {
                removeCallbacksAndMessages(null);
                return;
            }
            Holder holder = null;
            if (msg.obj instanceof Holder) {
                holder = (Holder) msg.obj;
            }
//            LLog.d(TAG, "handleMessage(" + msg + ")");
            switch (msg.what) {
                case MSG_PLAY:
                    play(holder);
                    break;
                case MSG_RELEASE:
                    release(holder);
                    break;
                case MSG_ITEM_POSITION_CHANGED:
                    itemPositionChanged();
                    break;
                default:
                    throw new RuntimeException("unsupported msg");
            }
        }

        public void msgPlay(boolean needCalcVisibility, Holder holder) {
            Message msg = obtainMessage(MSG_PLAY);
            msg.arg1 = needCalcVisibility ? 1 : 0;
            msg.obj = holder;
            sendMessage(msg);
        }

        public void msgRelease(Holder holder) {
            Message msg = obtainMessage(MSG_RELEASE);
            msg.obj = holder;
            sendMessage(msg);
        }

        public void msgItemPositionChanged() {
            if (hasMessages(MSG_ITEM_POSITION_CHANGED)) {
                removeMessages(MSG_ITEM_POSITION_CHANGED);
                LLog.d(TAG, "too much MSG_ITEM_POSITION_CHANGED");
            }
            sendEmptyMessage(MSG_ITEM_POSITION_CHANGED);
        }

        private void release(Holder holder) {
            if (holder.mYyTexturePlayer != null) {
                holder.mYyTexturePlayer.setOnMessageListener(null);
                holder.mYyTexturePlayer.releasePlayer();
                holder.mYyTexturePlayer = null;
            }
        }

        private void play(Holder holder) {
            if (holder.mYyTexturePlayer != null) {
                throw new RuntimeException("player must be released first");
            }
            holder.mYyTexturePlayer = new YYTexTurePlayer(mAdapter.get().mContext, null);
            holder.mYyTexturePlayer.setOnMessageListener(mPlayerListener);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(600, 600);
            lp.gravity = Gravity.CENTER;
            ((FrameLayout) holder.mItemView).addView(holder.mYyTexturePlayer.getView(), lp);
            holder.mYyTexturePlayer.playUrl(mAdapter.get().mData.get(holder.mPosition));
            mCurrent = holder.mPosition;
        }

        private void itemPositionChanged() {
            AbsListView mLv = mAdapter.get().mLv;
            int lvHeight = mAdapter.get().mLvHeight;
            if (mLv == null) {
                return;
            }
            posMapPercentage.clear();
            posMapHolder.clear();

            int childrenCount = mLv.getChildCount();
            int first = -1;
            for (int i = 0; i < childrenCount; i++) {
                View item = mLv.getChildAt(i);
                Holder holder = (Holder) item.getTag();
                if (mAdapter.get().getItemViewType(holder.mPosition) != Type.VIDEO.ordinal()) {
                    continue;
                }
                float percentage = calcVisiblePercent(holder.mItemView, holder.mWrapView, lvHeight);
                LLog.d(TAG, "" + percentage);
//                holder.mInfoTv.setText("");
                if (first == -1 && percentage >= PERCENTAGE_CAN_PLAY) {
                    first = holder.mPosition;
                }
                posMapPercentage.put(holder.mPosition, percentage);
                posMapHolder.put(holder.mPosition, holder);
            }

            int current = -1;
            if (mCurrent != -1) {
                current = mCurrent;
                float percentage = posMapPercentage.get(current);
                if (percentage < PERCENTAGE_CAN_PLAY) {
                    msgRelease(posMapHolder.get(current));
                    current = -1;

                    if (first != -1 && current == -1) {
                        current = first;
                        msgPlay(false, posMapHolder.get(current));
                    }
                }
            } else {
                if (first != -1 && current == -1) {
                    current = first;
                    msgPlay(false, posMapHolder.get(current));
                }
            }

            Set<Integer> positionSet = posMapPercentage.keySet();
            Iterator<Integer> iterator = positionSet.iterator();
            boolean hasTargetFound = false;
            while (iterator.hasNext()) {
                int next = iterator.next();
                if (current == next) {
                    hasTargetFound = true;
                } else if (posMapPercentage.get(next) >= PERCENTAGE_CAN_PLAY) {
                    mNext = next;
                    if (hasTargetFound) {
                        break;
                    }
                }
            }
        }

        public float calcVisiblePercent(View itemView, View wrapView, int boundaryOfHeight) {
            if (itemView == null) {
                throw new RuntimeException("list item view can not be null");
            }
            int top = 0, bottom = 0;
            if (wrapView != null) {
                top = wrapView.getTop();
                bottom = wrapView.getBottom();
                top += itemView.getTop();
                bottom += itemView.getTop();
            } else {
                top = itemView.getTop();
                bottom = itemView.getBottom();
            }
            int height = bottom - top;
            if (top < 0) {
                if (bottom < 0) {
                    return 0;
                } else if (bottom >= 0 && bottom < boundaryOfHeight) {
                    return (float) bottom / height;
                } else {
                    return (float) boundaryOfHeight / height;
                }
            } else if (top >= 0 && top < boundaryOfHeight) {
                if (bottom < boundaryOfHeight) {
                    return 1;
                } else {
                    return (float) (boundaryOfHeight - top) / height;
                }
            } else {
                return 0;
            }
        }
    }

    public static class YYPlayerMessageListener implements BasePlayer.OnMessageListener {
        private final String TAG = YYPlayerMessageListener.class.getSimpleName();
        private WeakReference<MainHandler> mMainHandler;

        public YYPlayerMessageListener(MainHandler handler) {
            mMainHandler = new WeakReference<MainHandler>(handler);
        }

        @Override
        public void handleMsg(BasePlayer.MsgParams msg) {
            if (mMainHandler.get() == null) {
                return;
            }
//            LLog.d(TAG, "handleMsg(" + logMsg(msg) + ")");
            switch (msg.type) {
                case BasePlayer.MSG_PLAY_ERROR:
                case BasePlayer.MSG_PLAY_HARDDECODERERROR:
                    break;
                case BasePlayer.MSG_PLAY_END:
                    completion();
                    break;
            }
        }

        private void error() {

        }

        private void completion() {
            if (mMainHandler.get().mNext == -1) {
                return;
            }
            mMainHandler.get().msgPlay(false, mMainHandler.get().posMapHolder.get(mMainHandler.get().mNext));
            LLog.d(TAG, "completion");
            mMainHandler.get().msgItemPositionChanged();
        }

        private String logMsg(BasePlayer.MsgParams msg) {
            if (msg == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("YYPlayerMsg:[").
                    append(msg.type).append(", ").
                    append(msg.param1).append(", ").
                    append(msg.param2).append(", ").
                    append(msg.param3).append(", ").
                    append(msg.bundle).
                    append("]");
            return new String(sb);
        }
    }

    public enum Type {
        VIDEO
    }

    private final       String TAG                 = YYPlayerListAdapter.class.getSimpleName();
    public static final float  PERCENTAGE_CAN_PLAY = 0.8f;

    private Context        mContext;
    private LayoutInflater mInflater;
    private MainHandler    mMainHandler;
//    private int mPlayingTarget = -1;

    private List<String> mData;

    public YYPlayerListAdapter(Context context, List<String> data) {
        super();
        mContext = context;
        mData = data;
        mInflater = LayoutInflater.from(mContext);
        mMainHandler = new MainHandler(this);
    }

    @Override
    public int getCount() {
        return mData.size();
    }

    @Override
    public Object getItem(int position) {
        return mData.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemViewType(int position) {
        return Type.VIDEO.ordinal();
    }

    @Override
    public int getViewTypeCount() {
        return Type.values().length;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (getItemViewType(position) == Type.VIDEO.ordinal()) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.yyplayer_list_item, null);
                holder = new Holder();
                holder.mItemView = convertView;
                holder.mInfoTv = (TextView) convertView.findViewById(R.id.info_tv);
            } else {
                holder = (Holder) convertView.getTag();
            }
            holder.mPosition = position;
            holder.mInfoTv.setText("");
            convertView.setTag(holder);
            return convertView;
        } else {
            throw new RuntimeException("unsupported item view type");
        }
    }

    private AbsListView mLv;
    private int         mScrollState;
    private int         mLvHeight;

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        String state = null;
        if (scrollState == SCROLL_STATE_FLING) {
            state = "SCROLL_STATE_FLING";
        } else if (scrollState == SCROLL_STATE_IDLE) {// 终点
            state = "SCROLL_STATE_IDLE";
            LLog.d(TAG, "onScrollStateChanged(" + state + ")");
            mMainHandler.msgItemPositionChanged();
        } else if (scrollState == SCROLL_STATE_TOUCH_SCROLL) {// 起点
            state = "SCROLL_STATE_TOUCH_SCROLL";
        }
        mScrollState = scrollState;
        if (mLv == null) {
            mLv = view;
            mLv.getWindowVisibleDisplayFrame(mItemRect);
            mLvHeight = mItemRect.height();
        }
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (mLv == null) {
            mLv = view;
            mLv.getWindowVisibleDisplayFrame(mItemRect);
            mLvHeight = mItemRect.height();
        }
        if (mScrollState != SCROLL_STATE_FLING) {
            LLog.d(TAG, "onScroll(" + firstVisibleItem + ", " + visibleItemCount + ", " + totalItemCount + ", " + mScrollState + ")");
            mMainHandler.msgItemPositionChanged();
        }
    }

    @Override
    public void onMovedToScrapHeap(View view) {
//        LLog.d(TAG, "" + view);
        Holder holder = (Holder) view.getTag();
        if (getItemViewType(holder.mPosition) == Type.VIDEO.ordinal()) {
            mMainHandler.msgRelease(holder);
        }
    }

    private Rect mItemRect = new Rect();
}
