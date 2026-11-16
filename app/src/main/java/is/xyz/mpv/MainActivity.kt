package `is`.xyz.mpv

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private fun isTelevision(): Boolean {
        val uiMode = resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setTitle(R.string.mpv_activity)
        // the TV library has its own heading, so the action bar would only repeat it
        if (isTelevision())
            supportActionBar?.hide()

        // The original plan was to have the file/doc picker live as fragments
        // under here but that requires refactoring I'm really not willing to figure out now.
        // ~sfan5, 2022-06-30

        if (savedInstanceState == null) {
            with (supportFragmentManager.beginTransaction()) {
                setReorderingAllowed(true)
                // a TV gets the thumbnail grid, everything else keeps the button home screen
                add(R.id.fragment_container_view,
                    if (isTelevision()) TvLibraryFragment() else MainScreenFragment())
                commit()
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // the Menu key opens the extra options of the TV library
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val tv = supportFragmentManager.findFragmentById(R.id.fragment_container_view)
                as? TvLibraryFragment
            if (tv != null) {
                tv.showMenu()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}
