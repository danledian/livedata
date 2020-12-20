package com.hs.publiclivedata

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.lifecycle.Observer
import com.hs.publiclivedata.util.DataRepository
import java.util.*

class StateActivity : AppCompatActivity() {

    companion object{
        val handler: Handler = Handler()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_state)

        DataRepository.data.observe(this, Observer {
            Log.i("TAG", "data:$it")
        })

        handler.postDelayed({
            DataRepository.data.postValue("${Random().nextInt(100)}")
        }, 1 * 1000)
    }
}