package com.vigil.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.vigil.android.databinding.ActivityXmlSampleBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class XmlSampleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXmlSampleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityXmlSampleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonBack.setOnClickListener {
            finish()
        }
    }
}
