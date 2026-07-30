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

import android.appwidget.AppWidgetProviderInfo;
import android.widget.RemoteViews;
import com.android.systemui.shared.communal.GlanceableHubWidget;

/**
 * Lets a trusted host outside SystemUI — in practice Launcher's "-1" overlay page — render the
 * Glanceable Hub's widgets.
 *
 * <p>App widget ids are owned by SystemUI's {@code AppWidgetHost} and are not transferable:
 * {@code AppWidgetServiceImpl} keys hosts by calling uid + package, so a remote host can neither
 * listen to nor mutate them directly. Instead SystemUI keeps the host and streams each widget's
 * {@link AppWidgetProviderInfo} and {@link RemoteViews} over this interface; the remote host
 * inflates them into its own {@code AppWidgetHostView}. Anything that requires host ownership
 * (resizing, configuration, add/delete) is proxied through methods here.
 *
 * <p>Guarded by {@code com.android.systemui.permission.ACCESS_GLANCEABLE_HUB}
 * (signature|privileged).
 */
interface IGlanceableHubOverlay {

    /**
     * Starts receiving the hub's widget list. The listener is called back immediately with the
     * current list. While at least one listener is registered SystemUI keeps its app widget host
     * listening, so callers must unregister when their surface goes away.
     */
    oneway void addWidgetsListener(in IWidgetsListener listener);

    /** Stops receiving the hub's widget list. */
    oneway void removeWidgetsListener(in IWidgetsListener listener);

    /**
     * Starts receiving {@link RemoteViews} for a single widget. The listener is called back with
     * the widget's current views. Registering here does not disturb the hub's own rendering of the
     * same widget — listeners are multiplexed.
     */
    oneway void setWidgetHostListener(int appWidgetId, in IWidgetHostListener listener);

    /** Stops receiving updates for a single widget. */
    oneway void removeWidgetHostListener(int appWidgetId, in IWidgetHostListener listener);

    /**
     * Reports the size the remote host is rendering a widget at, in dp, so SystemUI can forward it
     * to the provider. {@code AppWidgetHostView#updateAppWidgetSize} silently no-ops when called
     * by a non-owning uid, so remote hosts must route sizing through here.
     */
    oneway void updateWidgetSize(int appWidgetId, int minWidthDp, int minHeightDp, int maxWidthDp,
            int maxHeightDp);

    /** Launches the hub's widget editor. Requires a foreground, unlocked user. */
    oneway void startEditMode();

    /** Receives the hub's widget list. */
    oneway interface IWidgetsListener {
        void onWidgetsUpdated(in List<GlanceableHubWidget> widgets);
    }

    /**
     * Mirrors the parts of {@code AppWidgetHost.AppWidgetHostListener} a remote renderer needs.
     * Deferred updates are resolved on the SystemUI side — the host only ever sees resolved
     * {@link RemoteViews} — because {@code getAppWidgetViews} is uid-checked.
     */
    oneway interface IWidgetHostListener {
        void onUpdateProviderInfo(in @nullable AppWidgetProviderInfo appWidget);

        void updateAppWidget(in @nullable RemoteViews views);

        void onViewDataChanged(int viewId);
    }
}
