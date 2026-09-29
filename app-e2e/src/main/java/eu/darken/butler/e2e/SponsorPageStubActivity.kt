package eu.darken.butler.e2e

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Opens in place of a browser when a FOSS build's Sponsor button links to GitHub Sponsors. */
class SponsorPageStubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = TEXT })
    }

    companion object {
        const val TEXT = "Sponsor page stub"
    }
}
