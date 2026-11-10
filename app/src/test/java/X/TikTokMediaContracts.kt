package X

/** Original host annotation name, retention and value/alternate members. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class `02up`(val value: String, val alternate: Array<String> = [])

class TikTokMediaUrl(@JvmField @field:`02up`("url_list") var paths: List<String>)
class TikTokPhotoPage(@JvmField var urlModel: TikTokMediaUrl)
class TikTokPhotoInfo(@JvmField @field:`02up`("images") var imageList: List<TikTokPhotoPage>)
class TikTokMediaVideo {
    @JvmField @field:`02up`("play_addr") var playAddr: TikTokMediaUrl? = null
    @JvmField @field:`02up`("cover") var thumbnail: TikTokMediaUrl? = null
    @JvmField @field:`02up`("duration") var videoLength: Int = 1000
}
class TikTokMediaWork {
    @JvmField @field:`02up`("aweme_id", ["item_id"]) var identity: String = "native-work"
    @JvmField @field:`02up`("video") var _video: TikTokMediaVideo? = null
    @JvmField @field:`02up`("author") var creator: TikTokMediaAuthor = TikTokMediaAuthor()
    @JvmField var photoInfo: TikTokPhotoInfo? = null
    @JvmField var liveRoom: Any? = null
    @JvmField var advertisement: Boolean = false
    @JvmField var kind: Int = 0
    fun getAid(): String = identity
    fun getPhotoModeImageInfo(): TikTokPhotoInfo? = photoInfo
    fun getRoomFeedCellStruct(): Any? = liveRoom
    fun getAwemeType(): Int = kind
    fun getVideo(): TikTokMediaVideo? = _video
    fun isAd(): Boolean = advertisement
}
class TikTokMediaAuthor {
    @JvmField @field:`02up`("uid") var identifier: String = "creator-id"
    @JvmField @field:`02up`("nickname") var label: String = "Creator"
}
