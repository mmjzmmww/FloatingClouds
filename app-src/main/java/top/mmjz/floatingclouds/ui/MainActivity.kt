package top.mmjz.floatingclouds.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import top.mmjz.floatingclouds.databinding.LayoutMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: LayoutMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(binding.mainContainer.id, MainFragment())
                .commit()
        }
    }
}
