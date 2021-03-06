/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package net.spryservers.sprydav.ui

import android.content.*
import android.os.Handler
import android.os.IBinder
import net.spryservers.sprydav.settings.ISettings
import net.spryservers.sprydav.settings.ISettingsObserver
import net.spryservers.sprydav.settings.Settings

abstract class SettingsLoader<T>(
        context: Context
): AsyncTaskLoader<T>(context) {

    val handler = Handler()
    val settingsObserver = object: ISettingsObserver.Stub() {
        override fun onSettingsChanged() {
            handler.post {
                onContentChanged()
            }
        }
    }

    private var settingsSvc: ServiceConnection? = null
    var settings: ISettings? = null

    override fun onStartLoading() {
        if (settingsSvc != null)
            forceLoad()
        else {
            settingsSvc = object: ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                    settings = ISettings.Stub.asInterface(binder)
                    settings!!.registerObserver(settingsObserver)
                    onContentChanged()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    settings!!.unregisterObserver(settingsObserver)
                    settings = null
                }
            }
            context.bindService(Intent(context, Settings::class.java), settingsSvc, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onReset() {
        settingsSvc?.let {
            context.unbindService(it)
            settingsSvc = null
        }
    }

}