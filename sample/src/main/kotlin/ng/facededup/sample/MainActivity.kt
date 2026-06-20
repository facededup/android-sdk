package ng.facededup.sample

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

import ng.facededup.sdk.FacededupConfig
import ng.facededup.sdk.FacededupContract
import ng.facededup.sdk.FacededupResult
import ng.facededup.sdk.FacededupTheme

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val verify = registerForActivityResult(FacededupContract()) { r: FacededupResult? ->
        status.text = render(r)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Facededup Demo 2.0.0 (native)"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Tap to verify. The UI loads from the app — works even with no network (capture queues, verdict arrives by webhook on reconnect)."
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }
        val btn = Button(this).apply {
            text = "Verify your identity"
            setOnClickListener {
                status.text = "Launching…"
                verify.launch(
                    FacededupConfig(
                        baseUrl = "https://facededup.ai",
                        licenseKey = "fdk_40cT6S_lWEpiCjd22ls8bLsL_YQnZNzq",
                        subjectId = "demo-user-1",
                        // Grouped, typed branding via the new FacededupTheme API.
                        theme = FacededupTheme(
                            primaryColor = "#1E9C69",
                            backgroundColor = "#000000",
                            textColor = "#FFFFFF",
                            productName = "Facededup",
                        ),
                    )
                )
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(btn)
        setContentView(root)
    }

    private fun render(r: FacededupResult?): String {
        if (r == null) return "Cancelled or no result."
        if (r.outcome == "queued") {
            return "OFFLINE → QUEUED ✓\nCapture stored on-device. Reconnect and the verdict is delivered to your webhook."
        }
        return "outcome = " + r.outcome +
            "\nlive = " + r.isLive +
            "\nscore = " + r.score +
            "\npassed = " + r.passed
    }
}
