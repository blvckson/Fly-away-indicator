package com.blvckson.flyawayindicator

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    companion object { private const val CAPTURE=501 }

    private lateinit var status:TextView
    private lateinit var statement:TextView
    private lateinit var rounds:TextView

    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context?,intent:Intent?){
            status.text=intent?.getStringExtra(ScreenMonitorService.EXTRA_STATUS) ?: status.text
            statement.text=intent?.getStringExtra(ScreenMonitorService.EXTRA_STATEMENT) ?: ""
            rounds.text="Recorded rounds: ${intent?.getIntExtra(ScreenMonitorService.EXTRA_ROUNDS,0) ?: 0}"
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status=findViewById(R.id.status)
        statement=findViewById(R.id.statement)
        rounds=findViewById(R.id.rounds)

        findViewById<Button>(R.id.start).setOnClickListener{requestMonitoring()}
        findViewById<Button>(R.id.stop).setOnClickListener{
            stopService(Intent(this,ScreenMonitorService::class.java))
            status.text="Monitor stopped."
        }
        refreshRounds()
    }

    private fun requestMonitoring(){
        if(!Settings.canDrawOverlays(this)){
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status.text="Allow overlay permission, then press START MONITOR again."
            return
        }
        val pm=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(),CAPTURE)
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=CAPTURE)return
        if(data==null){
            status.text="Screen capture permission was not received."
            return
        }
        val service=Intent(this,ScreenMonitorService::class.java)
            .putExtra("resultCode",resultCode)
            .putExtra("data",data)
        startService(service)
        status.text="Monitor active — waiting for the Aviator live screen."
    }

    private fun refreshRounds(){
        val all=RoundRecordStore(this).all()
        rounds.text="Recorded rounds: ${all.size}"
        if(all.isEmpty()){
            statement.text="Statement Area: waiting for recorded Aviator rounds."
            return
        }
        val recent=all.takeLast(8).asReversed().joinToString("\n"){r->
            "Round ${r.round}: ${if(r.endingMultiplier.isBlank()) "end multiplier not read" else r.endingMultiplier} | Difference ${"%.0f".format(r.difference)}%"
        }
        statement.text="Statement Area\n$recent"
    }

    override fun onResume(){
        super.onResume()
        registerReceiver(receiver,IntentFilter(ScreenMonitorService.ACTION_STATUS))
        refreshRounds()
    }

    override fun onPause(){
        unregisterReceiver(receiver)
        super.onPause()
    }
}
