package com.jbion.android.lib.list.swipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.support.v4.view.MotionEventCompat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListAdapter;

// import com.nineoldandroids.animation.Animator;
// import com.nineoldandroids.animation.AnimatorListenerAdapter;
// import com.nineoldandroids.animation.AnimatorUpdateListener;
// import com.nineoldandroids.animation.ValueAnimator;
// import com.nineoldandroids.view.ViewHelper;
// import com.nineoldandroids.view.ViewPropertyAnimator;

/**
 * Touch listener impl for the SwipeListView
 */
class SwipeListViewTouchListener implements View.OnTouchListener {

    private static final String LOG_TAG = SwipeListViewTouchListener.class.getSimpleName();

    private static final int DISPLACE_CHOICE = 80;

    /**
     * Indicates no movement
     */
    private final static int STATE_REST = 0;
    /**
     * State scrolling x position
     */
    private final static int STATE_SCROLLING_X = 1;
    /**
     * State scrolling y position
     */
    private final static int STATE_SCROLLING_Y = 2;

    private final SwipeListView listView;
    private final SwipeOptions opts;

    // Cached ViewConfiguration and system-wide constant values
    private final int slop;
    private final int pageSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final long configShortAnimationTime;

    private int viewWidth = 1; // 1 and not 0 to prevent dividing by zero

    private List<PendingDismissData> pendingDismisses = new ArrayList<PendingDismissData>();
    private int dismissAnimationRefCount = 0;

    private boolean paused;
    private List<Boolean> opened = new ArrayList<Boolean>();
    private List<Boolean> openedRight = new ArrayList<Boolean>();
    private List<Boolean> checked = new ArrayList<Boolean>();

    private final SwipedItem movingItem = new SwipedItem();
    private final Motion currentMotion = new Motion();

    private int swipeCurrentAction;
    private int currentActionLeft;
    private int currentActionRight;

    private final Rect rect = new Rect();

    private class Motion {
        int scrollState = STATE_REST;
        float downX;
        float lastX;
        float lastY;

        boolean swiping;
        boolean swipingRight;
        VelocityTracker tracker;

        public void reset() {
            currentMotion.downX = 0;
            currentMotion.swiping = false;
            currentMotion.tracker.recycle();
            currentMotion.tracker = null;
        }
    }

    /**
     * Constructor
     * 
     * @param listView
     *            SwipeListView
     * @param options
     */
    public SwipeListViewTouchListener(SwipeListView listView, SwipeOptions options) {
        this.listView = listView;
        this.opts = options;

        Context ctx = listView.getContext();
        ViewConfiguration vc = ViewConfiguration.get(ctx);
        slop = vc.getScaledTouchSlop();
        pageSlop = vc.getScaledPagingTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        configShortAnimationTime = ctx.getResources().getInteger(
                android.R.integer.config_shortAnimTime);
        if (opts.animationTime <= 0) {
            opts.animationTime = configShortAnimationTime;
        }
        currentActionLeft = opts.swipeActionLeft;
        currentActionRight = opts.swipeActionRight;
        swipeCurrentAction = SwipeOptions.ACTION_NONE;
    }

    /**
     * Check is swiping is enabled
     * 
     * @return {@code true} if swipe is enabled
     */
    protected boolean isSwipeEnabled() {
        return opts.swipeMode != SwipeOptions.SWIPE_MODE_NONE;
    }

    /**
     * Adds new items when adapter is modified
     */
    public void resetItems() {
        if (listView.getAdapter() != null) {
            int count = listView.getAdapter().getCount();
            for (int i = opened.size(); i <= count; i++) {
                opened.add(false);
                openedRight.add(false);
                checked.add(false);
            }
        }
    }

    /**
     * Swap choice state in item
     * 
     * @param position
     *            position of list
     */
    private void swapCheckedState(int position) {
        Log.i(LOG_TAG, "Swapping checked state for position " + position);
        int lastCount = getCountSelected();
        boolean lastChecked = checked.get(position);
        checked.set(position, !lastChecked);
        int count = lastChecked ? lastCount - 1 : lastCount + 1;
        if (lastCount == 0 && count == 1) {
            listView.onChoiceStarted();
            closeOpenedItems();
            setActionsTo(SwipeOptions.ACTION_CHOICE);
        }
        if (lastCount == 1 && count == 0) {
            listView.onChoiceEnded();
            resetOldActions();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            listView.setItemChecked(position, !lastChecked);
        }
        listView.onChoiceChanged(position, !lastChecked);
        reloadChoiceStateInView(movingItem.frontView, position);
    }

    /**
     * Unselected choice state in item
     */
    protected void unselectedChoiceStates() {
        int start = listView.getFirstVisiblePosition();
        int end = listView.getLastVisiblePosition();
        for (int i = 0; i < checked.size(); i++) {
            if (checked.get(i) && i >= start && i <= end) {
                reloadChoiceStateInView(
                        listView.getChildAt(i - start).findViewById(opts.frontViewId), i);
            }
            checked.set(i, false);
        }
        listView.onChoiceEnded();
        resetOldActions();
    }

    /**
     * Unselected choice state in item
     */
    protected int dismiss(int position) {
        int start = listView.getFirstVisiblePosition();
        int end = listView.getLastVisiblePosition();
        View view = listView.getChildAt(position - start);
        ++dismissAnimationRefCount;
        if (position >= start && position <= end) {
            performDismiss(view, position, false);
            return view.getHeight();
        } else {
            pendingDismisses.add(new PendingDismissData(position, null));
            return 0;
        }
    }

    /**
     * Draw cell for display if item is selected or not
     * 
     * @param view
     *            the front view to reload
     * @param position
     *            position in list
     */
    protected void reloadChoiceStateInView(View view, int position) {
        if (isChecked(position)) {
            if (opts.drawableChecked > 0)
                view.setBackgroundResource(opts.drawableChecked);
        } else {
            if (opts.drawableUnchecked > 0)
                view.setBackgroundResource(opts.drawableUnchecked);
        }
    }

    /**
     * Get if item is selected
     * 
     * @param position
     *            position in list
     * @return {@code true} if item is selected
     */
    protected boolean isChecked(int position) {
        return position < checked.size() && checked.get(position);
    }

    /**
     * Count selected
     * 
     * @return the number of swiped items
     */
    protected int getCountSelected() {
        int count = 0;
        for (int i = 0; i < checked.size(); i++) {
            if (checked.get(i)) {
                count++;
            }
        }
        Log.d("SwipeListView", "selected: " + count);
        return count;
    }

    /**
     * Get positions selected
     * 
     * @return a list of the swiped positions
     */
    protected List<Integer> getSelectedPositions() {
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < checked.size(); i++) {
            if (checked.get(i)) {
                list.add(i);
            }
        }
        return list;
    }

    /**
     * Close all opened items
     */
    void closeOpenedItems() {
        int first = listView.getFirstVisiblePosition();
        int last = listView.getLastVisiblePosition();
        // animate all visible closing items
        for (int i = first; i <= last; i++) {
            closeAnimate(listView.getChildAt(i - first).findViewById(opts.frontViewId), i);
        }
        // close all items
        for (int i = 0; i < opened.size(); i++) {
            opened.set(i, false);
        }
    }

    /**
     * Open item
     * 
     * @param position
     *            Position of list
     */
    protected void open(int position) {
        int first = listView.getFirstVisiblePosition();
        int last = listView.getLastVisiblePosition();
        if (position >= first && position <= last) {
            // the affected view is visible
            openAnimate(listView.getChildAt(position - first).findViewById(opts.frontViewId),
                    position);
        } else {
            opened.set(position, true);
        }
    }

    /**
     * Close item
     * 
     * @param position
     *            Position of list
     */
    protected void close(int position) {
        int first = listView.getFirstVisiblePosition();
        int last = listView.getLastVisiblePosition();
        if (position >= first && position <= last) {
            // the affected view is visible
            closeAnimate(listView.getChildAt(position - first).findViewById(opts.frontViewId),
                    position);
        } else {
            opened.set(position, false);
        }
    }

    /**
     * Open item
     * 
     * @param view
     *            affected view
     * @param position
     *            Position of list
     */
    private void openAnimate(View view, int position) {
        if (!opened.get(position)) {
            generateRevealAnimate2(view, true, false, position);
        }
    }

    /**
     * Close item
     * 
     * @param view
     *            affected view
     * @param position
     *            Position of list
     */
    private void closeAnimate(View view, int position) {
        if (opened.get(position)) {
            generateRevealAnimate2(view, true, openedRight.get(position), position);
        }
    }

    /**
     * Create animation
     * 
     * @param view
     *            affected view
     * @param validMove
     *            If state should change. If "false" returns to the original position
     * @param toRight
     *            If swap is true, this parameter tells if move is to the right or
     *            left
     * @param position
     *            Position of list
     */
    private void generateAnimate(final View view, final boolean validMove, final boolean toRight,
            final int position) {
        Log.d("SwipeListView", "swap: " + validMove + " - swapRight: " + toRight + " - position: "
                + position);
        int action = opened.get(position) ? SwipeOptions.ACTION_REVEAL
                : toRight ? currentActionRight : currentActionLeft;
        if (action == SwipeOptions.ACTION_REVEAL) {
            generateRevealAnimate2(view, validMove, toRight, position);
        }
        if (action == SwipeOptions.ACTION_DISMISS) {
            generateDismissAnimate(movingItem.view, validMove, toRight, position);
        }
        if (action == SwipeOptions.ACTION_CHOICE) {
            generateChoiceAnimate(view, position);
        }
    }

    /**
     * Create choice animation
     * 
     * @param view
     *            affected view
     * @param position
     *            list position
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    private void generateChoiceAnimate(final View view, final int position) {
        view.animate().translationX(0).setDuration(opts.animationTime)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        currentMotion.scrollState = STATE_REST;
                        resetCell();
                    }
                });
    }

    /**
     * Create dismiss animation
     * 
     * @param view
     *            affected view
     * @param swap
     *            If will change state. If is "false" returns to the original
     *            position
     * @param swapRight
     *            If swap is true, this parameter tells if move is to the right or
     *            left
     * @param position
     *            Position of list
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    private void generateDismissAnimate(final View view, final boolean swap,
            final boolean swapRight, final int position) {
        int moveTo = 0;
        if (opened.get(position)) {
            if (!swap) {
                moveTo = calculateOpenOffset(openedRight.get(position));
            }
        } else {
            if (swap) {
                moveTo = calculateOpenOffset(swapRight);
            }
        }

        int alpha = 1;
        if (swap) {
            ++dismissAnimationRefCount;
            alpha = 0;
        }

        view.animate().translationX(moveTo).alpha(alpha).setDuration(opts.animationTime)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (swap) {
                            closeOpenedItems();
                            performDismiss(view, position, true);
                        }
                        resetCell();
                    }
                });

    }

    /**
     * Create reveal animation
     * 
     * @param view
     *            affected view
     * @param changeState
     *            If will change state. If "false" returns to the original position
     * @param toRight
     *            If swap is true, this parameter tells if movement is toward right
     *            or left
     * @param position
     *            list position
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    private void generateRevealAnimate2(final View view, final boolean changeState,
            final boolean toRight, final int position) {
        final boolean isOpen = opened.get(position);

        if (!opts.multipleSelectEnabled && changeState && !isOpen) {
            closeOpenedItems();
        }

        int moveTo = changeState ^ isOpen ? calculateOpenOffset(toRight) : 0;

        view.animate().translationX(moveTo).setDuration(opts.animationTime)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        currentMotion.scrollState = STATE_REST;
                        if (changeState && !isOpen) {
                            opened.set(position, true);
                            listView.onOpened(position, toRight);
                            openedRight.set(position, toRight);
                        } else if (changeState && isOpen) {
                            opened.set(position, false);
                            listView.onClosed(position, !toRight);
                        }
                        resetCell();
                    }
                });
    }

    /**
     * Create reveal animation
     * 
     * @param view
     *            affected view
     * @param swap
     *            If will change state. If "false" returns to the original position
     * @param swapRight
     *            If swap is true, this parameter tells if movement is toward right
     *            or left
     * @param position
     *            list position
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    private void generateRevealAnimateOld(final View view, final boolean swap,
            final boolean swapRight, final int position) {
        int moveTo = 0;
        boolean isOpen = opened.get(position);

        if (!opts.multipleSelectEnabled && !isOpen) {
            closeOpenedItems();
        }

        if (isOpen && !swap) {
            moveTo = calculateOpenOffset(openedRight.get(position));
        } else if (!isOpen && swap) {
            moveTo = calculateOpenOffset(swapRight);
        }

        view.animate().translationX(moveTo).setDuration(opts.animationTime)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        currentMotion.scrollState = STATE_REST;
                        if (swap) {
                            boolean closed = !opened.get(position);
                            opened.set(position, closed);
                            if (closed) {
                                listView.onOpened(position, swapRight);
                                openedRight.set(position, swapRight);
                            } else {
                                listView.onClosed(position, openedRight.get(position));
                            }
                        }
                        resetCell();
                    }
                });
    }

    /**
     * Calculates the X distance to move the view to its open position.
     * 
     * @param swapRight
     *            Whether opening to the right or left.
     * @return the X position of the open view relatively to the closed view.
     */
    private int calculateOpenOffset(boolean swapRight) {
        if (opts.swipeOffsetType == SwipeOptions.OFFSET_TYPE_TRAVELED) {
            return swapRight ? (int) (opts.swipeOffsetLeft) : (int) (-opts.swipeOffsetRight);
        } else {
            return swapRight ? (int) (viewWidth - opts.swipeOffsetRight)
                    : (int) (-viewWidth + opts.swipeOffsetLeft);
        }
    }

    private void resetCell() {
        if (movingItem.position != AdapterView.INVALID_POSITION) {
            if (swipeCurrentAction == SwipeOptions.ACTION_CHOICE) {
                movingItem.backView.setVisibility(View.VISIBLE);
            }
            movingItem.frontView.setClickable(opened.get(movingItem.position));
            movingItem.frontView.setLongClickable(opened.get(movingItem.position));
            movingItem.frontView = null;
            movingItem.backView = null;
            movingItem.position = AdapterView.INVALID_POSITION;
        }
    }

    /**
     * Set enabled
     * 
     * @param enabled
     */
    public void setEnabled(boolean enabled) {
        paused = !enabled;
    }

    /**
     * Return ScrollListener for ListView
     * 
     * @return OnScrollListener
     */
    public AbsListView.OnScrollListener makeScrollListener() {
        return new AbsListView.OnScrollListener() {

            @Override
            public void onScrollStateChanged(AbsListView absListView, int scrollState) {
                setEnabled(scrollState != AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                Log.d(LOG_TAG, "Scroll scroll");
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL || scrollState == SCROLL_STATE_FLING) {
                    Log.d(LOG_TAG, "Scroll scroll");
                    if (opts.closeAllItemsOnScroll) {
                        Log.d(LOG_TAG, "Scroll: close everything!");
                        closeOpenedItems();
                    }
                } else {
                    Log.d(LOG_TAG, "No scroll: reset");
                    movingItem.position = AdapterView.INVALID_POSITION;
                    currentMotion.scrollState = STATE_REST;
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
                /*
                Log.d(LOG_TAG, "first=" + firstVisibleItem + " last="
                        + (firstVisibleItem + visibleItemCount - 1));*/
            }
        };
    }

    /**
     * Check if the user is moving the cell
     * 
     * @param x
     *            Position X
     * @param y
     *            Position Y
     */
    private void updateScrollDirection(float x, float y) {
        final int xDiff = (int) Math.abs(x - currentMotion.lastX);
        final int yDiff = (int) Math.abs(y - currentMotion.lastY);

        boolean xMoved = xDiff > pageSlop;
        boolean yMoved = yDiff > pageSlop;

        if (xMoved || yMoved) {
            if (xDiff > yDiff) {
                currentMotion.scrollState = STATE_SCROLLING_X;
                Log.d(LOG_TAG, "update direction to X (xDiff=" + xDiff + ", yDiff=" + yDiff + ")");
            } else {
                currentMotion.scrollState = STATE_SCROLLING_Y;
                Log.d(LOG_TAG, "update direction to Y (xDiff=" + xDiff + ", yDiff=" + yDiff + ")");
            }
            currentMotion.lastX = x;
            currentMotion.lastY = y;
        }
    }

    /**
     * Initializes {@link #movingItem} fields with the data from the touched element
     * in the list. The touched element is determined based on the specified
     * {@link MotionEvent}'s coordinates.
     * 
     * @param ev
     *            The touch event to use to find the touched item.
     * @return {@code true} if an item was indeed found and initialized,
     *         {@code false} otherwise.
     */
    private boolean initTouchedItem(MotionEvent ev) {
        View item;
        int x = (int) ev.getX();
        int y = (int) ev.getY();
        // find the item located at (x,y)
        for (int i = 0; i < listView.getChildCount(); i++) {
            item = listView.getChildAt(i);
            item.getHitRect(rect);
            if (!rect.contains(x, y)) {
                continue; // not this one, keep searching
            }
            int touchedItemPosition = listView.getPositionForView(item);
            // don't allow swiping if this is on the header or footer or
            // IGNORE_ITEM_VIEW_TYPE or disabled item
            ListAdapter adapter = listView.getAdapter();
            if (adapter.isEnabled(touchedItemPosition)
                    && adapter.getItemViewType(touchedItemPosition) >= 0) {
                movingItem.view = item;
                movingItem.position = touchedItemPosition;
                movingItem.setFrontView(item.findViewById(opts.frontViewId));
                movingItem.frontView.setClickable(!opened.get(movingItem.position));
                movingItem.frontView.setLongClickable(!opened.get(movingItem.position));

                if (opts.backViewId > 0) {
                    movingItem.setBackView(item.findViewById(opts.backViewId));
                }
                return true;
            } else {
                movingItem.position = AdapterView.INVALID_POSITION;
                return false;
            }
        }
        movingItem.position = AdapterView.INVALID_POSITION;
        return false;
    }

    private void initCurrentMotion(MotionEvent motionEvent) {
        swipeCurrentAction = SwipeOptions.ACTION_NONE;
        currentMotion.swiping = false;
        currentMotion.downX = motionEvent.getX();
        boolean itemLoaded = initTouchedItem(motionEvent);
        if (itemLoaded) {
            currentMotion.tracker = VelocityTracker.obtain();
            currentMotion.tracker.addMovement(motionEvent);
        }
    }

    public boolean shouldIntercept(MotionEvent ev) {
        int action = MotionEventCompat.getActionMasked(ev);
        final float x = ev.getX();
        final float y = ev.getY();

        if (isSwipeEnabled()) {
            switch (action) {
            case MotionEvent.ACTION_DOWN:
                currentMotion.scrollState = STATE_REST;
                initCurrentMotion(ev);
                Log.d(LOG_TAG, "Intercept DOWN false");
                return false;
            case MotionEvent.ACTION_MOVE:
                updateScrollDirection(x, y);
                Log.d(LOG_TAG, "Intercept MOVE " + (currentMotion.scrollState == STATE_SCROLLING_X)
                        + " (state=" + currentMotion.scrollState + ")");
                return currentMotion.scrollState == STATE_SCROLLING_X;
            case MotionEvent.ACTION_UP:
                Log.d(LOG_TAG, "Intercept UP false");
                currentMotion.scrollState = STATE_REST;
                return false;
            case MotionEvent.ACTION_CANCEL:
                Log.d(LOG_TAG, "Intercept CANCEL false");
                currentMotion.scrollState = STATE_REST;
                return false;
            default:
                break;
            }
        }
        return false;
    }

    private boolean isValidXFling() {
        float velocityX = Math.abs(currentMotion.tracker.getXVelocity());
        float velocityY = Math.abs(currentMotion.tracker.getYVelocity());
        if (velocityX < minFlingVelocity || velocityX > maxFlingVelocity
                || velocityY * 2 > velocityX) {
            return false; // not an X-fling
        }

        boolean toRight = currentMotion.tracker.getXVelocity() > 0;

        if (opened.get(movingItem.position)) {
            if (openedRight.get(movingItem.position) && toRight) {
                // opened to right, flinging right
                return false;
            }
            if (!openedRight.get(movingItem.position) && !toRight) {
                // opened to left, flinging left
                return false;
            }
        } else {
            if (opts.swipeMode == SwipeOptions.SWIPE_MODE_LEFT && toRight) {
                // opens to left, flinging right
                return false;
            }
            if (opts.swipeMode == SwipeOptions.SWIPE_MODE_RIGHT && !toRight) {
                // opens to right, flinging left
                return false;
            }
        }

        return true;
    }

    /**
     * @see android.view.View.OnTouchListener#onTouch(android.view.View,
     *      android.view.MotionEvent)
     */
    @Override
    public boolean onTouch(View view, MotionEvent ev) {
        if (!isSwipeEnabled()) {
            Log.v(LOG_TAG, "onTouch XXXX returns false (swipe disabled)");
            return false;
        }

        if (viewWidth < 2) {
            viewWidth = listView.getWidth();
        }

        switch (MotionEventCompat.getActionMasked(ev)) {
        case MotionEvent.ACTION_DOWN:
            if (paused && movingItem.position != AdapterView.INVALID_POSITION) {
                Log.v(LOG_TAG, "onTouch DOWN returns false");
                // feature disabled for now
                return false;
            }
            initCurrentMotion(ev);
            Log.d(LOG_TAG, "onTouch DOWN returns true");
            return true;

        case MotionEvent.ACTION_MOVE: {
            if (paused || movingItem.position == AdapterView.INVALID_POSITION) {
                Log.v(LOG_TAG, "onTouch MOVE returns false");
                return false;
            }

            currentMotion.tracker.addMovement(ev);
            float deltaX = ev.getX() - currentMotion.downX;

            if (!currentMotion.swiping) {
                currentMotion.tracker.computeCurrentVelocity(1000);
                float velocityX = Math.abs(currentMotion.tracker.getXVelocity());
                float velocityY = Math.abs(currentMotion.tracker.getYVelocity());

                if (getAllowedDelta(deltaX) > slop && velocityY < velocityX) {
                    currentMotion.swiping = true;
                    currentMotion.swipingRight = (deltaX > 0);
                    Log.v("SwipeListView", "deltaX: " + deltaX + " - swipingRight: "
                            + currentMotion.swipingRight);
                    if (opened.get(movingItem.position)) {
                        swipeCurrentAction = SwipeOptions.ACTION_REVEAL;
                    } else {
                        if (currentMotion.swipingRight) {
                            swipeCurrentAction = currentActionRight;
                        } else {
                            swipeCurrentAction = currentActionLeft;
                        }
                    }
                    if (swipeCurrentAction == SwipeOptions.ACTION_CHOICE) {
                        movingItem.backView.setVisibility(View.GONE);
                    } else {
                        movingItem.backView.setVisibility(View.VISIBLE);
                    }
                }
            }

            if (currentMotion.swiping && movingItem.position != AdapterView.INVALID_POSITION) {
                if (opened.get(movingItem.position)) {
                    deltaX += calculateOpenOffset(openedRight.get(movingItem.position));
                }
                move(deltaX);
                Log.v(LOG_TAG, "onTouch MOVE returns true");
                return true;
            }
            break;
        }

        case MotionEvent.ACTION_UP: {
            if (!currentMotion.swiping || movingItem.position == AdapterView.INVALID_POSITION) {
                Log.v(LOG_TAG, "onTouch UP returns false");
                // we were not following this event
                return false;
            }

            float deltaX = ev.getX() - currentMotion.downX;
            currentMotion.tracker.addMovement(ev);
            currentMotion.tracker.computeCurrentVelocity(1000);

            boolean toRight = deltaX > 0;
            boolean validMove = false;

            if (Math.abs(deltaX) > viewWidth / 2) {
                validMove = true;
            } else if (isValidXFling()) {
                validMove = true;
                // may be different from the one calculated with deltaX
                // (if the item is pulled one way and flung towards the other side)
                toRight = currentMotion.tracker.getXVelocity() > 0;
            }
            generateAnimate(movingItem.frontView, validMove, toRight, movingItem.position);
            if (swipeCurrentAction == SwipeOptions.ACTION_CHOICE) {
                swapCheckedState(movingItem.position);
            }

            currentMotion.reset();
            break;
        }
        }
        return false;
    }

    private float getAllowedDelta(float deltaX) {
        if (opts.swipeMode == SwipeOptions.SWIPE_MODE_NONE) {
            // no swipe
            return 0;
        } else if (opts.swipeMode != SwipeOptions.SWIPE_MODE_BOTH) {
            if (opened.get(movingItem.position)) {
                if ((opts.swipeMode == SwipeOptions.SWIPE_MODE_LEFT && deltaX < 0)
                        || (opts.swipeMode == SwipeOptions.SWIPE_MODE_RIGHT && deltaX > 0)) {
                    // trying to close the element the wrong way
                    return 0;
                }
            } else {
                if ((opts.swipeMode == SwipeOptions.SWIPE_MODE_LEFT && deltaX > 0)
                        || (opts.swipeMode == SwipeOptions.SWIPE_MODE_RIGHT && deltaX < 0)) {
                    // trying to open the element the wrong way
                    return 0;
                }
            }
        } else {
            // always a swipe in BOTH mode
        }
        return Math.abs(deltaX);
    }

    private void setActionsTo(int action) {
        currentActionRight = action;
        currentActionLeft = action;
    }

    protected void resetOldActions() {
        currentActionRight = opts.swipeActionRight;
        currentActionLeft = opts.swipeActionLeft;
    }

    /**
     * Moves the view
     * 
     * @param deltaX
     *            delta
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void move(float deltaX) {
        listView.onMove(movingItem.position, deltaX);
        float posX = movingItem.frontView.getX();
        if (opened.get(movingItem.position)) {
            posX += calculateOpenOffset(openedRight.get(movingItem.position));
        }
        if (posX > 0 && !currentMotion.swipingRight) {
            Log.d("SwipeListView", "change to right");
            currentMotion.swipingRight = true;
            swipeCurrentAction = currentActionRight;
            movingItem.backView
                    .setVisibility(swipeCurrentAction == SwipeOptions.ACTION_CHOICE ? View.GONE
                            : View.VISIBLE);
        } else if (posX < 0 && currentMotion.swipingRight) {
            Log.d("SwipeListView", "change to left");
            currentMotion.swipingRight = false;
            swipeCurrentAction = currentActionLeft;
            movingItem.backView
                    .setVisibility(swipeCurrentAction == SwipeOptions.ACTION_CHOICE ? View.GONE
                            : View.VISIBLE);
        }
        if (swipeCurrentAction == SwipeOptions.ACTION_DISMISS) {
            movingItem.view.setTranslationX(deltaX);
            movingItem.view.setAlpha(Math.max(0f,
                    Math.min(1f, 1f - 2f * Math.abs(deltaX) / viewWidth)));
        } else if (swipeCurrentAction == SwipeOptions.ACTION_CHOICE) {
            if ((currentMotion.swipingRight && deltaX > 0 && posX < DISPLACE_CHOICE)
                    || (!currentMotion.swipingRight && deltaX < 0 && posX > -DISPLACE_CHOICE)
                    || (currentMotion.swipingRight && deltaX < DISPLACE_CHOICE)
                    || (!currentMotion.swipingRight && deltaX > -DISPLACE_CHOICE)) {
                movingItem.frontView.setTranslationX(deltaX);
            }
        } else {
            movingItem.frontView.setTranslationX(deltaX);
        }
    }

    /**
     * Perform dismiss action
     * 
     * @param dismissView
     *            View
     * @param dismissPosition
     *            Position of list
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    protected void performDismiss(final View dismissView, final int dismissPosition,
            boolean doPendingDismiss) {
        final ViewGroup.LayoutParams lp = dismissView.getLayoutParams();
        final int originalHeight = dismissView.getHeight();

        ValueAnimator animator = ValueAnimator.ofInt(originalHeight, 1).setDuration(
                opts.animationTime);

        if (doPendingDismiss) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    --dismissAnimationRefCount;
                    if (dismissAnimationRefCount == 0) {
                        removePendingDismisses(originalHeight);
                    }
                }
            });
        }

        animator.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                lp.height = (Integer) valueAnimator.getAnimatedValue();
                dismissView.setLayoutParams(lp);
            }
        });

        pendingDismisses.add(new PendingDismissData(dismissPosition, dismissView));
        animator.start();
    }

    protected void resetPendingDismisses() {
        pendingDismisses.clear();
    }

    protected void handlerPendingDismisses(final int originalHeight) {
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                removePendingDismisses(originalHeight);
            }
        }, opts.animationTime + 100);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void removePendingDismisses(int originalHeight) {
        // No active animations, process all pending dismisses.
        // Sort by descending position
        Collections.sort(pendingDismisses);

        int[] dismissPositions = new int[pendingDismisses.size()];
        for (int i = pendingDismisses.size() - 1; i >= 0; i--) {
            dismissPositions[i] = pendingDismisses.get(i).position;
        }
        listView.onDismiss(dismissPositions);

        ViewGroup.LayoutParams lp;
        for (PendingDismissData pendingDismiss : pendingDismisses) {
            // Reset view presentation
            if (pendingDismiss.view != null) {
                pendingDismiss.view.setAlpha(1f);
                pendingDismiss.view.setTranslationX(0);
                lp = pendingDismiss.view.getLayoutParams();
                lp.height = originalHeight;
                pendingDismiss.view.setLayoutParams(lp);
            }
        }

        resetPendingDismisses();

    }

    /**
     * Class that saves pending dismiss data
     */
    private class PendingDismissData implements Comparable<PendingDismissData> {
        private int position;
        private View view;

        public PendingDismissData(int position, View view) {
            this.position = position;
            this.view = view;
        }

        @Override
        public int compareTo(PendingDismissData other) {
            // Sort by descending position
            return other.position - position;
        }
    }

    private class SwipedItem {
        public View view;
        public View frontView;
        public View backView;
        public int position;

        /**
         * Sets current item's front view
         * 
         * @param frontView
         *            Front view
         */
        public void setFrontView(View frontView) {
            this.frontView = frontView;
            this.frontView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listView.onClickFrontView(movingItem.position);
                }
            });
            if (opts.openOnLongClick) {
                movingItem.frontView.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        open(movingItem.position);
                        return false;
                    }
                });
            }
        }

        /**
         * Set current item's back view
         * 
         * @param backView
         */
        public void setBackView(View backView) {
            this.backView = backView;
            this.backView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listView.onClickBackView(movingItem.position);
                }
            });
        }
    }

}