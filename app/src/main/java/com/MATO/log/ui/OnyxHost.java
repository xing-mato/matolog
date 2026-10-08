package com.MATO.log.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.MATO.log.R;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;
import com.MATO.log.util.DateUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索容器。
 *
 * 内部维护一条「跨度栈」：年 → 月 → 周 → 日（最多四层）。
 * 点某一格 = 进入二级界面；返回键逐层退回。
 * 上一段 / 下一段按当前层跨度整体平移，也可以左右滑动切换。
 */
public class OnyxHost extends FrameLayout {

    public interface Listener {
        /** 当前跨度与锚点发生变化 */
        void onScopeChanged(int level, long anchor);

        /** 点开一条事件 */
        void onEventClick(Event event);

        /** 长按一条事件 */
        void onEventLongClick(Event event);
    }

    /** 一个跨度界面 + 它绑定的数据 */
    public class ViewAdapter {
        public final int level;
        public final LevelView view;
        public final ArrayList<Event> events = new ArrayList<>();
        public ArrayList<View> children = new ArrayList<>();

        ViewAdapter(int level, LevelView view) {
            this.level = level;
            this.view = view;
        }

        public LevelView view() {
            return view;
        }

        public ArrayList<Event> events() {
            return events;
        }

        public ArrayList<View> children() {
            return children;
        }

        public void setChildren(ArrayList<View> list) {
            if (list == null) {
                list = new ArrayList<>();
            }
            this.children = list;
        }

        /** 当前锚点 */
        public long anchor() {
            return OnyxHost.this.anchor;
        }

        /** 整条栈的锚点（各层都用自己的粒度去解释它） */
        public long[] stackAnchors() {
            long[] out = new long[stack.size()];
            for (int i = 0; i < stack.size(); i++) {
                out[i] = anchor;
            }
            return out;
        }

        /** 自己下面一层是否正在展示 */
        public boolean isTop() {
            return !stack.isEmpty() && stack.get(stack.size() - 1) == this;
        }
    }

    private final ArrayList<ViewAdapter> stack = new ArrayList<>();
    private LevelFactory factory;
    private Listener listener;
    private DbHelper db;

    private int level = Views.LEVEL_DAY;
    private long anchor = DateUtil.startOfToday();

    private int lastW;
    private int lastH;
    private boolean started;

    /**
     * 当前实际的跨度层次，外→内。例如从月历点进某一天，就是 [月, 周, 日]。
     * 它才是权威结构：rootLevel 只用于「切换跨度」按钮。
     */
    private final ArrayList<Integer> path = new ArrayList<>();

    private float downX;
    private float downY;
    private boolean swiping;
    private boolean holdFired;
    private final int touchSlop;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (swiping || holdFired) {
                return;
            }
            holdFired = true;
            Event e = eventAt(downX, downY);
            if (e != null && listener != null) {
                listener.onEventLongClick(e);
            }
        }
    };

    public OnyxHost(Context context) {
        this(context, null);
    }

    public OnyxHost(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        path.add(Integer.valueOf(level));
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setFactory(LevelFactory f) {
        this.factory = f;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public Listener listener() {
        return listener;
    }

    public void setDb(DbHelper db) {
        this.db = db;
    }

    public DbHelper db() {
        return db;
    }

    public int getLevel() {
        return level;
    }

    public long getAnchor() {
        return anchor;
    }

    public int depth() {
        return stack.size();
    }
    public boolean isTopLevelShown(int lv) {
        ViewAdapter a = adapterOf(lv);
        return a != null && a.isTop();
    }

    /** 取某一层的适配器（父层靠它判定“某一格是否处于选中态”） */
    public ViewAdapter adapterOf(int lv) {
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i).level == lv) {
                return stack.get(i);
            }
        }
        return null;
    }

    /** 某个跨度界面登记它的可点格，供本宿主做点击命中判定 */
    void registerCells(int lv, ArrayList<View> list) {
        ViewAdapter a = adapterOf(lv);
        if (a != null) {
            a.setChildren(list);
        }
        // 这一次渲染要不要逐项浮现，据此把整页的格子依次点亮
        if (cascadeNext && lv == currentLevel()) {
            cascadeNext = false;
            cascadeIn(list);
        }
    }

    // ---------------- 导航 ----------------

    /** 切换检索跨度：整条层次回到该跨度的最外层，并做一次「换页」过渡 */
    public void setLevel(int newLevel) {
        if (newLevel < Views.LEVEL_DAY || newLevel > Views.LEVEL_YEAR || newLevel == level) {
            return;
        }
        boolean forward = Views.depthOf(newLevel) > Views.depthOf(level);
        level = newLevel;
        path.clear();
        path.add(Integer.valueOf(newLevel));
        cascadeNext = true;
        rebuild();
        animateTransition(forward ? 1 : -1, 30f, true);
    }

    public void goToday() {
        anchor = DateUtil.startOfToday();
        cascadeNext = true;
        rebuild();
    }

    /** 上一段 / 下一段：按当前所在层的跨度整体平移，层次不变 */
    public void move(int direction) {
        if (direction == 0 || factory == null) {
            return;
        }
        for (int i = 0; i < stack.size(); i++) {
            stack.get(i).view.onSwipeHorizontal(direction > 0);
        }
        anchor = Views.step(currentLevel(), anchor, direction);
        bindStack();
        notifyScope();
        // 换段时给一点方向感：跟手的方向滑入，距离比换页小
        animateTransition(direction > 0 ? 1 : -1, 22f, false);
    }

    /** 供各跨度界面里的「上一段 / 下一段」按钮调用 */
    public void step(int direction) {
        move(direction);
    }

    /** 当前所在层（层次栈最内层） */
    private int currentLevel() {
        return path.isEmpty() ? level : path.get(path.size() - 1).intValue();
    }

    /**
     * 实际正在展示的跨度。
     *
     * 跟 {@link #getLevel()} 的区别：getLevel() 是顶部四段里选中的那一段，
     * 从「月」一路点进某一天时它仍然是「月」；而这个方法返回的是真正在看的「日」。
     * 界面判定（片段高亮、「新建」显隐）要用它。
     */
    public int currentDepth() {
        return currentLevel();
    }

    /** 点某一格 → 压入下一层（二级界面） */
    public void drill(long tappedMillis) {
        if (factory == null) {
            return;
        }
        int cur = currentLevel();
        if (!Views.canPush(cur)) {
            return;
        }
        int child = Views.childLevelOf(cur);
        anchor = tappedMillis;
        // 层次里已经有这一层就复用，否则压入
        boolean has = false;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).intValue() == child) {
                has = true;
                break;
            }
        }
        if (!has) {
            path.add(Integer.valueOf(child));
        }
        cascadeNext = true;
        buildStack();
        bindStack();
        notifyScope();
        animateTransition(1, 30f, true);
    }

    /** 退出一层；已在最外层返回 false */
    public boolean popStack() {
        return popStackAnimated();
    }

    /** 带一点收起的动效退出一层 */
    public boolean popStackAnimated() {
        if (path.size() <= 1) {
            return false;
        }
        path.remove(path.size() - 1);
        cascadeNext = true;
        buildStack();
        bindStack();
        notifyScope();
        animateTransition(-1, 30f, true);
        return true;
    }

    /** 数据变化后重新渲染整条栈 */
    public void refresh() {
        cascadeNext = false;
        rebuild();
    }

    /**
     * 这一次渲染要不要让列表逐项浮现。
     * 只有「整页换掉」（换跨度、下钻、返回、换段）才铺开；
     * 单纯重绘（删了一条记录）不该整页抖一下。
     */
    private boolean cascadeNext;

    // ---------------- 构建 ----------------

    private void rebuild() {
        if (factory == null || !started) {
            return;
        }
        buildStack();
        bindStack();
        notifyScope();
    }

    /** 按当前层次重建跨度栈，视图实例尽量复用 */
    private void buildStack() {
        if (path.isEmpty()) {
            path.add(Integer.valueOf(level));
        }
        ArrayList<ViewAdapter> next = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            int lv = path.get(i).intValue();
            ViewAdapter exist = adapterOf(lv);
            if (exist != null) {
                next.add(exist);
            } else {
                LevelView v = factory.make(lv, this);
                next.add(new ViewAdapter(lv, v));
            }
        }

        // 正在展示的那一层先记下来：换页时它要反向退开淡出，而不是被直接抹掉。
        // 新的层次里如果没有它（换跨度、返回上一层），它就留到过渡结束再摘。
        ArrayList<ViewAdapter> outgoing = new ArrayList<>(1);
        ViewAdapter topNow = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        if (topNow != null && !next.contains(topNow)) {
            outgoing.add(topNow);
        }

        // 先摘掉所有旧视图，但这两类别摘：
        // 1) 这一次要退场、正压在最上面的那层；
        // 2) 新层次里还要复用的那些（下面会重新挂上去）。
        ArrayList<View> keep = new ArrayList<>();
        for (int i = 0; i < outgoing.size(); i++) {
            keep.add(outgoing.get(i).view.getRoot());
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            boolean used = false;
            for (int k = 0; k < next.size(); k++) {
                if (next.get(k).view.getRoot() == child) {
                    used = true;
                    break;
                }
            }
            if (!used && !keep.contains(child)) {
                removeView(child);
            }
        }
        stack.clear();
        stack.addAll(next);

        for (int i = 0; i < stack.size(); i++) {
            View root = stack.get(i).view.getRoot();
            if (root.getParent() instanceof android.view.ViewGroup
                    && root.getParent() != this) {
                ((android.view.ViewGroup) root.getParent()).removeView(root);
            }
            if (root.getParent() == this) {
                // 还在容器里（退场中又被打断），移到最上面当新层用
                removeView(root);
            }
            addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            root.setAlpha(1f);
            root.setTranslationX(0f);
            root.setVisibility(i == stack.size() - 1 ? VISIBLE : INVISIBLE);
        }

        // 上一轮还在退场的那一层：这次重建要把它彻底摘掉，免得越叠越多
        for (int i = 0; i < outgoingViews.size(); i++) {
            View stale = outgoingViews.get(i).view.getRoot();
            if (stale.getParent() == this && !keep.contains(stale)) {
                stale.animate().cancel();
                removeView(stale);
                stale.setAlpha(1f);
                stale.setTranslationX(0f);
            }
        }

        // 退场层压在最上面，遮住刚装好的新层；淡出时新层就透出来了
        for (int i = 0; i < outgoing.size(); i++) {
            View root = outgoing.get(i).view.getRoot();
            if (root.getParent() instanceof android.view.ViewGroup
                    && root.getParent() != this) {
                ((android.view.ViewGroup) root.getParent()).removeView(root);
            }
            if (root.getParent() == this) {
                removeView(root);
            }
            addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            root.setVisibility(VISIBLE);
        }
        outgoingViews = outgoing;
    }

    /** 上一次换页里正在退场的层，过渡结束后摘掉 */
    private ArrayList<ViewAdapter> outgoingViews = new ArrayList<>(1);

    private void bindStack() {
        for (int i = 0; i < stack.size(); i++) {
            stack.get(i).view.bind(anchor);
        }
    }

    private void notifyScope() {
        if (listener != null) {
            listener.onScopeChanged(level, anchor);
        }
    }

    /**
     * 页面切换的过渡：新内容按方向滑入淡入，同时被替换掉的那一层朝反方向退开淡出。
     *
     * 两层一起动才有「一层压着一层」的层次感；只动新层的话，旧层是啪地消失，
     * 观感上就是生硬。曲线与非线性的时长统一取自 {@link Motion}。
     *
     * @param direction 1 = 往下一段 / 往里进（从右滑入）；-1 = 往上一段 / 往后退（从左滑入）
     * @param distanceDp 起始偏移量
     * @param crossFade  是否让旧层退场（换跨度、下钻、返回用；单纯换段不铺两层）
     */
    private void animateTransition(int direction, float distanceDp, boolean crossFade) {
        if (stack.isEmpty()) {
            return;
        }
        final View incoming = stack.get(stack.size() - 1).view.getRoot();
        Motion.enterPage(incoming, direction, distanceDp);

        if (!crossFade || outgoingViews.isEmpty()) {
            return;
        }
        final ArrayList<ViewAdapter> leaving = outgoingViews;
        outgoingViews = new ArrayList<>(1);
        for (int i = 0; i < leaving.size(); i++) {
            final View v = leaving.get(i).view.getRoot();
            Motion.exitPage(v, direction, distanceDp * 0.6f);
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (v.getParent() == OnyxHost.this) {
                        removeView(v);
                    }
                    v.setAlpha(1f);
                    v.setTranslationX(0f);
                }
            }, Motion.EXIT_MS + 20);
        }
    }

    /**
     * 整层内容重新渲染后，让列表里的一行行依次浮现。
     * 只有「整页换掉」才用，单纯重绘（例如删了一条）不该整页抖一下。
     */
    private void cascadeIn(ArrayList<View> cells) {
        if (cells == null || cells.isEmpty()) {
            return;
        }
        for (int i = 0; i < cells.size(); i++) {
            Motion.enterItem(cells.get(i), i);
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (started && w > 0 && h > 0 && (w != lastW || h != lastH)) {
            lastW = w;
            lastH = h;
            post(new Runnable() {
                @Override
                public void run() {
                    rebuild();
                }
            });
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!started) {
            started = true;
            post(new Runnable() {
                @Override
                public void run() {
                    rebuild();
                }
            });
        }
    }

    // ---------------- 手势 ----------------
    // 宿主自己消费整段手势：拖动 = 切换上一段/下一段，轻点 = 交给下面的格处理。
    // 这样嵌在 ScrollView 里也不会和子视图抢事件。

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float density = getResources().getDisplayMetrics().density;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiping = false;
                holdFired = false;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout() + 60);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (!swiping && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    swiping = true;
                    removeCallbacks(longPress);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (!swiping && (Math.abs(dx) > touchSlop * 2 || Math.abs(dy) > touchSlop * 2)) {
                    removeCallbacks(longPress);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                float dx = ev.getX() - downX;
                if (swiping) {
                    float threshold = Math.max(getWidth() * 0.15f, 48 * density);
                    if (Math.abs(dx) > threshold) {
                        move(dx < 0 ? 1 : -1);
                    }
                    swiping = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                } else if (!holdFired) {
                    handleTap(ev.getX(), ev.getY());
                }
                holdFired = false;
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                swiping = false;
                holdFired = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            default:
                return super.onTouchEvent(ev);
        }
    }

    /** 把坐标换算到某一格上：事件条目 → onEventClick，普通格 → onTap */
    private void handleTap(float x, float y) {
        View hit = findCell(x, y);
        if (hit == null || stack.isEmpty()) {
            return;
        }
        if (hit.getTag(R.id.tag_kind) != null) {
            Event e = findEvent(hit.getTag(R.id.tag_day));
            if (e != null && listener != null) {
                listener.onEventClick(e);
            }
            return;
        }
        Object tag = hit.getTag(R.id.tag_day);
        if (tag instanceof Long) {
            stack.get(stack.size() - 1).view.onTap(((Long) tag).longValue());
        }
    }

    private Event eventAt(float x, float y) {
        View hit = findCell(x, y);
        if (hit == null || hit.getTag(R.id.tag_kind) == null) {
            return null;
        }
        return findEvent(hit.getTag(R.id.tag_day));
    }

    /**
     * 事件条目上挂的数值是事件 id；可点格上挂的是当天零点。
     * 两者用同一个 tag，靠 tag_kind 区分。
     */
    private Event findEvent(Object key) {
        if (!(key instanceof Long) || stack.isEmpty()) {
            return null;
        }
        long id = (Long) key;
        List<Event> events = stack.get(stack.size() - 1).events;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).id == id) {
                return events.get(i);
            }
        }
        return null;
    }

    /** 命中测试：当前最上层的可点格 */
    private View findCell(float x, float y) {
        if (stack.isEmpty()) {
            return null;
        }
        ViewAdapter top = stack.get(stack.size() - 1);
        int[] self = new int[2];
        getLocationOnScreen(self);
        int[] loc = new int[2];
        for (int i = 0; i < top.children.size(); i++) {
            View c = top.children.get(i);
            if (c == null || c.getVisibility() != VISIBLE || c.getWidth() == 0) {
                continue;
            }
            c.getLocationOnScreen(loc);
            float cx = loc[0] - self[0];
            float cy = loc[1] - self[1];
            if (x >= cx && x <= cx + c.getWidth() && y >= cy && y <= cy + c.getHeight()) {
                return c;
            }
        }
        return null;
    }
}
