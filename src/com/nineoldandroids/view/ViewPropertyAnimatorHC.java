/*
 * Copyright (C) 2011 The Android Open Source Project Licensed under the Apache
 * License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or
 * agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nineoldandroids.view;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.View;
import android.view.animation.Interpolator;

import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.AnimatorListener;
import com.nineoldandroids.animation.AnimatorUpdateListener;
import com.nineoldandroids.animation.ValueAnimator;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
@SuppressWarnings("unchecked")
class ViewPropertyAnimatorHC extends ViewPropertyAnimator {

    /**
     * A WeakReference holding the View whose properties are being animated by this
     * class. This is set at construction time.
     */
    private final WeakReference<View> mView;

    /**
     * The duration of the underlying Animator object. By default, we don't set the
     * duration on the Animator and just use its default duration. If the duration is
     * ever set on this Animator, then we use the duration that it was set to.
     */
    private long mDuration;

    /**
     * A flag indicating whether the duration has been set on this object. If not, we
     * don't set the duration on the underlying Animator, but instead just use its
     * default duration.
     */
    private boolean mDurationSet = false;

    /**
     * The startDelay of the underlying Animator object. By default, we don't set the
     * startDelay on the Animator and just use its default startDelay. If the
     * startDelay is ever set on this Animator, then we use the startDelay that it
     * was set to.
     */
    private long mStartDelay = 0;

    /**
     * A flag indicating whether the startDelay has been set on this object. If not,
     * we don't set the startDelay on the underlying Animator, but instead just use
     * its default startDelay.
     */
    private boolean mStartDelaySet = false;

    /**
     * The interpolator of the underlying Animator object. By default, we don't set
     * the interpolator on the Animator and just use its default interpolator. If the
     * interpolator is ever set on this Animator, then we use the interpolator that
     * it was set to.
     */
    private/* Time */Interpolator mInterpolator;

    /**
     * A flag indicating whether the interpolator has been set on this object. If
     * not, we don't set the interpolator on the underlying Animator, but instead
     * just use its default interpolator.
     */
    private boolean mInterpolatorSet = false;

    /**
     * Listener for the lifecycle events of the underlying
     */
    private AnimatorListener mListener = null;

    /**
     * This listener is the mechanism by which the underlying Animator causes changes
     * to the properties currently being animated, as well as the cleanup after an
     * animation is complete.
     */
    private AnimatorEventListener mAnimatorEventListener = new AnimatorEventListener();

    /**
     * This list holds the properties that have been asked to animate. We allow the
     * caller to request several animations prior to actually starting the underlying
     * animator. This enables us to run one single animator to handle several
     * properties in parallel. Each property is tossed onto the pending list until
     * the animation actually starts (which is done by posting it onto mView), at
     * which time the pending list is cleared and the properties on that list are
     * added to the list of properties associated with that animator.
     */
    private ArrayList<NameValuesHolder> mPendingAnimations = new ArrayList<NameValuesHolder>();

    /**
     * Constants used to associate a property being requested and the mechanism used
     * to set the property (this class calls directly into View to set the properties
     * in question).
     */
    private static final int NONE = 0x0000;
    private static final int TRANSLATION_X = 0x0001;
    private static final int ALPHA = 0x0200;

    private static final int TRANSFORM_MASK = TRANSLATION_X;

    /**
     * The mechanism by which the user can request several properties that are then
     * animated together works by posting this Runnable to start the underlying
     * Animator. Every time a property animation is requested, we cancel any previous
     * postings of the Runnable and re-post it. This means that we will only ever run
     * the Runnable (and thus start the underlying animator) after the caller is done
     * setting the properties that should be animated together.
     */
    private Runnable mAnimationStarter = new Runnable() {
        @Override
        public void run() {
            startAnimation();
        }
    };

    /**
     * This class holds information about the overall animation being run on the set
     * of properties. The mask describes which properties are being animated and the
     * values holder is the list of all property/value objects.
     */
    private static class PropertyBundle {
        int mPropertyMask;
        ArrayList<NameValuesHolder> mNameValuesHolder;

        PropertyBundle(int propertyMask, ArrayList<NameValuesHolder> nameValuesHolder) {
            mPropertyMask = propertyMask;
            mNameValuesHolder = nameValuesHolder;
        }

        /**
         * Removes the given property from being animated as a part of this
         * PropertyBundle. If the property was a part of this bundle, it returns true
         * to indicate that it was, in fact, canceled. This is an indication to the
         * caller that a cancellation actually occurred.
         * 
         * @param propertyConstant
         *            The property whose cancellation is requested.
         * @return true if the given property is a part of this bundle and if it has
         *         therefore been canceled.
         */
        boolean cancel(int propertyConstant) {
            if ((mPropertyMask & propertyConstant) != 0 && mNameValuesHolder != null) {
                int count = mNameValuesHolder.size();
                for (int i = 0; i < count; ++i) {
                    NameValuesHolder nameValuesHolder = mNameValuesHolder.get(i);
                    if (nameValuesHolder.mNameConstant == propertyConstant) {
                        mNameValuesHolder.remove(i);
                        mPropertyMask &= ~propertyConstant;
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * This list tracks the list of properties being animated by any particular
     * animator. In most situations, there would only ever be one animator running at
     * a time. But it is possible to request some properties to animate together,
     * then while those properties are animating, to request some other properties to
     * animate together. The way that works is by having this map associate the group
     * of properties being animated with the animator handling the animation. On
     * every update event for an Animator, we ask the map for the associated
     * properties and set them accordingly.
     */
    private HashMap<Animator, PropertyBundle> mAnimatorMap = new HashMap<Animator, PropertyBundle>();

    /**
     * This is the information we need to set each property during the animation.
     * mNameConstant is used to set the appropriate field in View, and the from/delta
     * values are used to calculate the animated value for a given animation fraction
     * during the animation.
     */
    private static class NameValuesHolder {
        int mNameConstant;
        float mFromValue;
        float mDeltaValue;

        NameValuesHolder(int nameConstant, float fromValue, float deltaValue) {
            mNameConstant = nameConstant;
            mFromValue = fromValue;
            mDeltaValue = deltaValue;
        }
    }

    /**
     * Constructor, called by View. This is private by design, as the user should
     * only get a ViewPropertyAnimator by calling View.animate().
     * 
     * @param view
     *            The View associated with this ViewPropertyAnimator
     */
    ViewPropertyAnimatorHC(View view) {
        mView = new WeakReference<View>(view);
    }

    /**
     * Sets the duration for the underlying animator that animates the requested
     * properties. By default, the animator uses the default value for ValueAnimator.
     * Calling this method will cause the declared value to be used instead.
     * 
     * @param duration
     *            The length of ensuing property animations, in milliseconds. The
     *            value cannot be negative.
     * @return This object, allowing calls to methods in this class to be chained.
     */
    @Override
    public ViewPropertyAnimator setDuration(long duration) {
        if (duration < 0) {
            throw new IllegalArgumentException("Animators cannot have negative duration: "
                    + duration);
        }
        mDurationSet = true;
        mDuration = duration;
        return this;
    }

    @Override
    public ViewPropertyAnimator setListener(AnimatorListener listener) {
        mListener = listener;
        return this;
    }

    @Override
    public ViewPropertyAnimator translationX(float value) {
        animateProperty(TRANSLATION_X, value);
        return this;
    }

    @Override
    public ViewPropertyAnimator alpha(float value) {
        animateProperty(ALPHA, value);
        return this;
    }

    /**
     * Starts the underlying Animator for a set of properties. We use a single
     * animator that simply runs from 0 to 1, and then use that fractional value to
     * set each property value accordingly.
     */
    private void startAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(1.0f);
        ArrayList<NameValuesHolder> nameValueList = (ArrayList<NameValuesHolder>) mPendingAnimations
                .clone();
        mPendingAnimations.clear();
        int propertyMask = 0;
        int propertyCount = nameValueList.size();
        for (int i = 0; i < propertyCount; ++i) {
            NameValuesHolder nameValuesHolder = nameValueList.get(i);
            propertyMask |= nameValuesHolder.mNameConstant;
        }
        mAnimatorMap.put(animator, new PropertyBundle(propertyMask, nameValueList));
        animator.addUpdateListener(mAnimatorEventListener);
        animator.addListener(mAnimatorEventListener);
        if (mStartDelaySet) {
            animator.setStartDelay(mStartDelay);
        }
        if (mDurationSet) {
            animator.setDuration(mDuration);
        }
        if (mInterpolatorSet) {
            animator.setInterpolator(mInterpolator);
        }
        animator.start();
    }

    /**
     * Utility function, called by the various x(), y(), etc. methods. This stores
     * the constant name for the property along with the from/delta values that will
     * be used to calculate and set the property during the animation. This structure
     * is added to the pending animations, awaiting the eventual start() of the
     * underlying animator. A Runnable is posted to start the animation, and any
     * pending such Runnable is canceled (which enables us to end up starting just
     * one animator for all of the properties specified at one time).
     * 
     * @param constantName
     *            The specifier for the property being animated
     * @param toValue
     *            The value to which the property will animate
     */
    private void animateProperty(int constantName, float toValue) {
        float fromValue = getValue(constantName);
        float deltaValue = toValue - fromValue;
        animatePropertyBy(constantName, fromValue, deltaValue);
    }

    /**
     * Utility function, called by animateProperty() and animatePropertyBy(), which
     * handles the details of adding a pending animation and posting the request to
     * start the animation.
     * 
     * @param constantName
     *            The specifier for the property being animated
     * @param startValue
     *            The starting value of the property
     * @param byValue
     *            The amount by which the property will change
     */
    private void animatePropertyBy(int constantName, float startValue, float byValue) {
        // First, cancel any existing animations on this property
        if (mAnimatorMap.size() > 0) {
            Animator animatorToCancel = null;
            Set<Animator> animatorSet = mAnimatorMap.keySet();
            for (Animator runningAnim : animatorSet) {
                PropertyBundle bundle = mAnimatorMap.get(runningAnim);
                if (bundle.cancel(constantName)) {
                    // property was canceled - cancel the animation if it's now empty
                    // Note that it's safe to break out here because every new
                    // animation
                    // on a property will cancel a previous animation on that
                    // property, so
                    // there can only ever be one such animation running.
                    if (bundle.mPropertyMask == NONE) {
                        // the animation is no longer changing anything - cancel it
                        animatorToCancel = runningAnim;
                        break;
                    }
                }
            }
            if (animatorToCancel != null) {
                animatorToCancel.cancel();
            }
        }

        NameValuesHolder nameValuePair = new NameValuesHolder(constantName, startValue, byValue);
        mPendingAnimations.add(nameValuePair);
        View v = mView.get();
        if (v != null) {
            v.removeCallbacks(mAnimationStarter);
            v.post(mAnimationStarter);
        }
    }

    /**
     * This method handles setting the property values directly in the View object's
     * fields. propertyConstant tells it which property should be set, value is the
     * value to set the property to.
     * 
     * @param propertyConstant
     *            The property to be set
     * @param value
     *            The value to set the property to
     */
    private void setValue(int propertyConstant, float value) {
        // final View.TransformationInfo info = mView.mTransformationInfo;
        View v = mView.get();
        if (v != null) {
            switch (propertyConstant) {
            case TRANSLATION_X:
                // info.mTranslationX = value;
                v.setTranslationX(value);
                break;
            case ALPHA:
                // info.mAlpha = value;
                v.setAlpha(value);
                break;
            }
        }
    }

    /**
     * This method gets the value of the named property from the View object.
     * 
     * @param propertyConstant
     *            The property whose value should be returned
     * @return float The value of the named property
     */
    private float getValue(int propertyConstant) {
        // final View.TransformationInfo info = mView.mTransformationInfo;
        View v = mView.get();
        if (v != null) {
            switch (propertyConstant) {
            case TRANSLATION_X:
                // return info.mTranslationX;
                return v.getTranslationX();
            case ALPHA:
                // return info.mAlpha;
                return v.getAlpha();
            }
        }
        return 0;
    }

    /**
     * Utility class that handles the various Animator events. The only ones we care
     * about are the end event (which we use to clean up the animator map when an
     * animator finishes) and the update event (which we use to calculate the current
     * value of each property and then set it on the view object).
     */
    private class AnimatorEventListener implements AnimatorListener, AnimatorUpdateListener {
        @Override
        public void onAnimationStart(Animator animation) {
            if (mListener != null) {
                mListener.onAnimationStart(animation);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (mListener != null) {
                mListener.onAnimationCancel(animation);
            }
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
            if (mListener != null) {
                mListener.onAnimationRepeat(animation);
            }
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (mListener != null) {
                mListener.onAnimationEnd(animation);
            }
            mAnimatorMap.remove(animation);
            // If the map is empty, it means all animation are done or canceled, so
            // the listener
            // isn't needed anymore. Not nulling it would cause it to leak any
            // objects used in
            // its implementation
            if (mAnimatorMap.isEmpty()) {
                mListener = null;
            }
        }

        /**
         * Calculate the current value for each property and set it on the view.
         * Invalidate the view object appropriately, depending on which properties
         * are being animated.
         * 
         * @param animation
         *            The animator associated with the properties that need to be
         *            set. This animator holds the animation fraction which we will
         *            use to calculate the current value of each property.
         */
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            // alpha requires slightly different treatment than the other (transform)
            // properties.
            // The logic in setAlpha() is not simply setting mAlpha, plus the
            // invalidation
            // logic is dependent on how the view handles an internal call to
            // onSetAlpha().
            // We track what kinds of properties are set, and how alpha is handled
            // when it is
            // set, and perform the invalidation steps appropriately.
            // boolean alphaHandled = false;
            // mView.invalidateParentCaches();
            float fraction = animation.getAnimatedFraction();
            PropertyBundle propertyBundle = mAnimatorMap.get(animation);
            int propertyMask = propertyBundle.mPropertyMask;
            if ((propertyMask & TRANSFORM_MASK) != 0) {
                View v = mView.get();
                if (v != null) {
                    v.invalidate(/* false */);
                }
            }
            ArrayList<NameValuesHolder> valueList = propertyBundle.mNameValuesHolder;
            if (valueList != null) {
                int count = valueList.size();
                for (int i = 0; i < count; ++i) {
                    NameValuesHolder values = valueList.get(i);
                    float value = values.mFromValue + fraction * values.mDeltaValue;
                    // if (values.mNameConstant == ALPHA) {
                    // alphaHandled = mView.setAlphaNoInvalidation(value);
                    // } else {
                    setValue(values.mNameConstant, value);
                    // }
                }
            }
            /*
             * if ((propertyMask & TRANSFORM_MASK) != 0) {
             * mView.mTransformationInfo.mMatrixDirty = true; mView.mPrivateFlags |=
             * View.DRAWN; // force another invalidation }
             */
            // invalidate(false) in all cases except if alphaHandled gets set to true
            // via the call to setAlphaNoInvalidation(), above
            View v = mView.get();
            if (v != null) {
                v.invalidate(/* alphaHandled */);
            }
        }
    }
}
