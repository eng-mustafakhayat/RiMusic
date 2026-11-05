package it.fast4x.piped.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime as JavaLocalDateTime
import kotlinx.datetime.LocalDateTime as KotlinxLocalDateTime

@Serializable
data class Instance(
    val name: String,
    @SerialName("api_url")
    val apiBaseUrl: UrlString,
    @SerialName("locations")
    val locationsFormatted: String,
    val version: String,
    @SerialName("up_to_date")
    val upToDate: Boolean,
    @SerialName("cdn")
    val isCdn: Boolean,
    @SerialName("registered")
    val userCount: Long,
    @SerialName("last_checked")
    val lastChecked: DateTimeSeconds,
    @SerialName("cache")
    val hasCache: Boolean,
    @SerialName("s3_enabled")
    val usesS3: Boolean,
    @SerialName("image_proxy_url")
    val imageProxyBaseUrl: UrlString,
    @SerialName("registration_disabled")
    val registrationDisabled: Boolean
) {
    constructor(
        name: String,
        apiBaseUrl: UrlString,
        locationsFormatted: String,
        version: String,
        upToDate: Boolean,
        isCdn: Boolean,
        userCount: Long,
        lastChecked: JavaLocalDateTime,
        hasCache: Boolean,
        usesS3: Boolean,
        imageProxyBaseUrl: UrlString,
        registrationDisabled: Boolean
    ) : this(
        name = name,
        apiBaseUrl = apiBaseUrl,
        locationsFormatted = locationsFormatted,
        version = version,
        upToDate = upToDate,
        isCdn = isCdn,
        userCount = userCount,
        lastChecked = KotlinxLocalDateTime(
            year = lastChecked.year,
            monthNumber = lastChecked.monthValue,
            dayOfMonth = lastChecked.dayOfMonth,
            hour = lastChecked.hour,
            minute = lastChecked.minute,
            second = lastChecked.second,
            nanosecond = lastChecked.nano
        ),
        hasCache = hasCache,
        usesS3 = usesS3,
        imageProxyBaseUrl = imageProxyBaseUrl,
        registrationDisabled = registrationDisabled
    )
}
