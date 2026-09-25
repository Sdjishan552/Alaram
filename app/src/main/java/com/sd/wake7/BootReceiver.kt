package com.sd.wake7
import android.content.*
class BootReceiver: BroadcastReceiver(){ override fun onReceive(context:Context,intent:Intent){ AlarmScheduler.schedule(context) } }
