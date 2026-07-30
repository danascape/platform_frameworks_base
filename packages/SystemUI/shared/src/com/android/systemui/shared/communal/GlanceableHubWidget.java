/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shared.communal;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A single widget on the Glanceable Hub, as published to out-of-process hosts such as Launcher.
 *
 * <p>This is a flattened, dependency-free mirror of SystemUI's internal
 * {@code CommunalWidgetContentModel}. It deliberately does not expose SystemUI internals so that
 * the cross-process contract can evolve independently of the hub implementation.
 *
 * <p>The widget ids carried here belong to SystemUI's {@code AppWidgetHost}. A remote host may
 * render them (see {@link IGlanceableHubOverlay}) but may not call {@code AppWidgetManager}
 * mutators against them — those calls are uid-checked and will silently no-op or throw.
 */
public final class GlanceableHubWidget implements Parcelable {

    /** The widget is bound and ready to render. {@link #getProviderInfo()} is non-null. */
    public static final int STATE_AVAILABLE = 0;
    /** The widget's app is still installing. {@link #getComponentName()} is non-null. */
    public static final int STATE_PENDING = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_AVAILABLE, STATE_PENDING})
    public @interface State {}

    private final int mAppWidgetId;
    @State private final int mState;
    private final int mRank;
    private final int mSpanY;
    @Nullable private final AppWidgetProviderInfo mProviderInfo;
    @Nullable private final ComponentName mComponentName;
    @Nullable private final Bitmap mIcon;
    private final UserHandle mUser;

    /** Creates a widget in {@link #STATE_AVAILABLE}. */
    public static GlanceableHubWidget available(int appWidgetId,
            AppWidgetProviderInfo providerInfo, int rank, int spanY, UserHandle user) {
        return new GlanceableHubWidget(appWidgetId, STATE_AVAILABLE, rank, spanY, providerInfo,
                providerInfo.provider, /* icon= */ null, user);
    }

    /** Creates a widget in {@link #STATE_PENDING}. */
    public static GlanceableHubWidget pending(int appWidgetId, ComponentName componentName,
            @Nullable Bitmap icon, int rank, int spanY, UserHandle user) {
        return new GlanceableHubWidget(appWidgetId, STATE_PENDING, rank, spanY,
                /* providerInfo= */ null, componentName, icon, user);
    }

    private GlanceableHubWidget(int appWidgetId, @State int state, int rank, int spanY,
            @Nullable AppWidgetProviderInfo providerInfo, @Nullable ComponentName componentName,
            @Nullable Bitmap icon, UserHandle user) {
        mAppWidgetId = appWidgetId;
        mState = state;
        mRank = rank;
        mSpanY = spanY;
        mProviderInfo = providerInfo;
        mComponentName = componentName;
        mIcon = icon;
        mUser = user;
    }

    private GlanceableHubWidget(Parcel in) {
        mAppWidgetId = in.readInt();
        mState = in.readInt();
        mRank = in.readInt();
        mSpanY = in.readInt();
        mProviderInfo = in.readTypedObject(AppWidgetProviderInfo.CREATOR);
        mComponentName = in.readTypedObject(ComponentName.CREATOR);
        mIcon = in.readTypedObject(Bitmap.CREATOR);
        mUser = in.readTypedObject(UserHandle.CREATOR);
    }

    /** The id of this widget in SystemUI's app widget host. */
    public int getAppWidgetId() {
        return mAppWidgetId;
    }

    @State
    public int getState() {
        return mState;
    }

    /** Position of this widget in the hub, ascending. */
    public int getRank() {
        return mRank;
    }

    /** Height of this widget in hub grid rows. */
    public int getSpanY() {
        return mSpanY;
    }

    /** Non-null when {@link #getState()} is {@link #STATE_AVAILABLE}. */
    @Nullable
    public AppWidgetProviderInfo getProviderInfo() {
        return mProviderInfo;
    }

    /** The widget provider. Always non-null. */
    @Nullable
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /** Placeholder icon shown while the widget's app is installing. */
    @Nullable
    public Bitmap getIcon() {
        return mIcon;
    }

    /** The user that owns this widget. */
    public UserHandle getUser() {
        return mUser;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAppWidgetId);
        dest.writeInt(mState);
        dest.writeInt(mRank);
        dest.writeInt(mSpanY);
        dest.writeTypedObject(mProviderInfo, flags);
        dest.writeTypedObject(mComponentName, flags);
        dest.writeTypedObject(mIcon, flags);
        dest.writeTypedObject(mUser, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GlanceableHubWidget)) return false;
        GlanceableHubWidget other = (GlanceableHubWidget) o;
        return mAppWidgetId == other.mAppWidgetId
                && mState == other.mState
                && mRank == other.mRank
                && mSpanY == other.mSpanY
                && Objects.equals(mComponentName, other.mComponentName)
                && Objects.equals(mUser, other.mUser);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAppWidgetId, mState, mRank, mSpanY, mComponentName, mUser);
    }

    @Override
    public String toString() {
        return "GlanceableHubWidget{id=" + mAppWidgetId
                + ", state=" + (mState == STATE_AVAILABLE ? "available" : "pending")
                + ", rank=" + mRank
                + ", spanY=" + mSpanY
                + ", provider=" + mComponentName
                + "}";
    }

    public static final Creator<GlanceableHubWidget> CREATOR = new Creator<>() {
        @Override
        public GlanceableHubWidget createFromParcel(Parcel in) {
            return new GlanceableHubWidget(in);
        }

        @Override
        public GlanceableHubWidget[] newArray(int size) {
            return new GlanceableHubWidget[size];
        }
    };
}
