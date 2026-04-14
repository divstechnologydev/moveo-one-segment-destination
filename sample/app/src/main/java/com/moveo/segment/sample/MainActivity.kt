package com.moveo.segment.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.moveo.segment.sample.databinding.ActivityMainBinding
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val eventLog = ArrayDeque<String>(8)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvAnonymousId.text = SampleApp.analytics.anonymousId()

        with(binding) {
            btnIdentify.setOnClickListener         { fireIdentify() }
            btnTrackAddToCart.setOnClickListener   { fireTrackAddToCart() }
            btnTrackPurchase.setOnClickListener    { fireTrackPurchase() }
            btnTrackViewProduct.setOnClickListener { fireTrackViewProduct() }
            btnScreenHome.setOnClickListener       { fireScreenHome() }
            btnScreenProduct.setOnClickListener    { fireScreenProduct() }
            btnGroup.setOnClickListener            { fireGroup() }
        }
    }

    private fun fireIdentify() {
        SampleApp.analytics.identify(
            userId = "user-123",
            traits = buildJsonObject {
                put("name", "Jane Doe")
                put("email", "jane@example.com")
                put("plan", "pro")
            }
        )
        log("IDENTIFY  userId=user-123")
    }

    private fun fireTrackAddToCart() {
        SampleApp.analytics.track(
            name = "Add to Cart",
            properties = buildJsonObject {
                put("product_id", "PROD-001")
                put("product_name", "Demo Product")
                put("price", 29.99)
                put("currency", "USD")
            }
        )
        log("TRACK     Add to Cart")
    }

    private fun fireTrackPurchase() {
        SampleApp.analytics.track(
            name = "Order Completed",
            properties = buildJsonObject {
                put("order_id", "ORD-${(1000..9999).random()}")
                put("revenue", 59.98)
                put("currency", "USD")
            }
        )
        log("TRACK     Order Completed")
    }

    private fun fireTrackViewProduct() {
        SampleApp.analytics.track(
            name = "Product Viewed",
            properties = buildJsonObject {
                put("product_id", "PROD-001")
                put("product_name", "Demo Product")
                put("category", "Electronics")
            }
        )
        log("TRACK     Product Viewed")
    }

    private fun fireScreenHome() {
        SampleApp.analytics.screen(
            title = "Home",
            properties = buildJsonObject {
                put("referrer", "push_notification")
            }
        )
        log("SCREEN    Home")
    }

    private fun fireScreenProduct() {
        SampleApp.analytics.screen(
            title = "Product Detail",
            properties = buildJsonObject {
                put("product_id", "PROD-001")
            }
        )
        log("SCREEN    Product Detail")
    }

    private fun fireGroup() {
        SampleApp.analytics.group(
            groupId = "company-42",
            traits = buildJsonObject {
                put("name", "Acme Corp")
                put("industry", "Technology")
                put("employees", 150)
            }
        )
        log("GROUP     company-42")
    }

    private fun log(message: String) {
        val time = timeFormat.format(Date())
        eventLog.addFirst("[$time]  $message")
        if (eventLog.size > 8) eventLog.removeLast()
        binding.tvEventLog.text = eventLog.joinToString("\n")
    }
}
