/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * Copyright (C) 2017 Mario Danic <mario@lovelyhq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.utils;

import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

public class NotificationUtils {
    public static final String NOTIFICATION_CHANNEL_CALLS = "NOTIFICATION_CHANNEL_CALLS";
    public static final String NOTIFICATION_CHANNEL_MESSAGES = "NOTIFICATION_CHANNEL_MESSAGES";
    private static final String TAG = "NotificationUtils";

    @TargetApi(Build.VERSION_CODES.O)
    public static void createNotificationChannel(NotificationManager notificationManager,
                                                 String channelId, String channelName,
                                                 String channelDescription, boolean vibrate,
                                                 int importance, Uri soundUri) {

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && notificationManager.getNotificationChannel(channelId) == null) {

            NotificationChannel channel = new NotificationChannel(channelId, channelName,
                    importance);

            int usage;

            if (channelId.equals(NotificationUtils.NOTIFICATION_CHANNEL_CALLS)) {
                usage =  AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST;
            } else {
                usage = AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT;
            }
            
            channel.setSound(soundUri, new AudioAttributes.Builder().setUsage(usage).build());
            channel.setDescription(channelDescription);
            channel.enableLights(vibrate);
            channel.enableVibration(vibrate);

            channel.setLightColor(Color.RED);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    public static void createNotificationChannelGroup(NotificationManager notificationManager,
                                                      String groupId, CharSequence groupName) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannelGroup notificationChannelGroup = new NotificationChannelGroup(groupId, groupName);
            if (!notificationManager.getNotificationChannelGroups().contains(notificationChannelGroup)) {
                notificationManager.createNotificationChannelGroup(notificationChannelGroup);
            }
        }
    }
}
