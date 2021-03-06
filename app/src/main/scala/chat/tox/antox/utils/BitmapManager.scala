package chat.tox.antox.utils

import java.io.{File, FileInputStream, FileNotFoundException, InputStream}
import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import android.graphics.BitmapFactory.{Options => BitmapOptions}
import android.graphics.{Bitmap, BitmapFactory}
import android.support.v4.util.LruCache
import android.util.Log
import android.widget.ImageView
import chat.tox.antox.utils.BitmapManager._
import chat.tox.antox.wrapper.BitmapUtils.RichBitmap
import org.scaloid.common._
import rx.lang.scala.Observable
import rx.lang.scala.schedulers.{NewThreadScheduler, AndroidMainThreadScheduler, IOScheduler}

import scala.collection.mutable

object BitmapManager {
  // Use a LRU Cache for storing inlined bitmap images in chats
  private var mMemoryCache: LruCache[String, Bitmap] = _

  // Use a separate hashmap for avatars as they are all needed most of the time
  private val mAvatarCache: mutable.HashMap[String, Bitmap] = new mutable.HashMap[String, Bitmap]

  // Hashmap used for storing whether a cached avatar is valid or needs to be updated because a contact
  // has updated their avatar - contacts' avatars are stored under the name of their public key
  private val mAvatarValid: mutable.HashMap[String, Boolean] = new mutable.HashMap[String, Boolean]()

  private val TAG = LoggerTag(getClass.getSimpleName)

  private def getFromCache(isAvatar: Boolean, key: String): Option[Bitmap] = {
    if (isAvatar) {
      getAvatarFromCache(key)
    } else {
      getBitmapFromMemCache(key)
    }
  }

  private def getBitmapFromMemCache(key: String): Option[Bitmap] = {
    Option(mMemoryCache.get(key))
  }

  private def getAvatarFromCache(key: String): Option[Bitmap] = {
    if (isAvatarValid(key)) {
      mAvatarCache.get(key)
    } else {
      None
    }
  }

  private def isAvatarValid(key: String): Boolean = {
    mAvatarValid.getOrElse(key, false)
  }

  private def addBitmapToMemoryCache(key: String, bitmap: Bitmap) {
    if (mMemoryCache != null && getBitmapFromMemCache(key).isEmpty) {
      mMemoryCache.put(key, bitmap)
    }
  }

  private def addAvatarToCache(key: String, bitmap: Bitmap) {
    mAvatarCache.put(key, bitmap) // will overwrite any previous value for key
    mAvatarValid.put(key, true)
  }

  def setAvatarInvalid(file: File) {
    val key = file.getPath + file.getName
    mAvatarValid.put(key, false)
  }

  def calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int): Int = {
    val width = options.outWidth
    var inSampleSize = 1

    if (width > reqWidth) {
      val halfWidth = width / 2
      while ((halfWidth / inSampleSize) > reqWidth) {
        inSampleSize *= 2
      }
    }
    AntoxLog.debug("Using a sample size of " + inSampleSize, TAG)

    inSampleSize
  }

  /**
   * Will decode the byte Array and proceed to return the bitmap or null if the byte array
   * could not be decoded
   */
  def decodeAndCheck(byteArr: Array[Byte], options: BitmapOptions): Boolean = {
    options.inJustDecodeBounds = true
    BitmapFactory.decodeByteArray(byteArr, 0, byteArr.length, options)

    options.outWidth > 0 && options.outHeight > 0
  }

  /**
   * Reads in bytes from the given stream and returns them in an array
   */
  private def getBytesFromStream(inputStream: InputStream): Array[Byte] = {
    var byteArr = Array.ofDim[Byte](0)
    val buffer = Array.ofDim[Byte](2 ^ 10)
    var len: Int = 0
    var count = 0

    try {
      len = inputStream.read(buffer)
      while (len > -1) {
        if (len != 0) {
          if (count + len > byteArr.length) {
            val newbuf = Array.ofDim[Byte]((count + len) * 2)
            System.arraycopy(byteArr, 0, newbuf, 0, count)
            byteArr = newbuf
          }
          System.arraycopy(buffer, 0, byteArr, count, len)
          count += len
        }
        len = inputStream.read(buffer)
      }
      byteArr
    } catch {
      case e: Exception =>
        e.printStackTrace()
        null
    }
  }

  /**
   * Will load the bitmap from file, decode it and then return a potentially downsampled bitmap
   * ready to be displayed
   */
  private def decodeBitmap(file: File, imageKey: String, isAvatar: Boolean): Bitmap = {
    var fis: FileInputStream = null

    try {
      // Get a stream to the file
      fis = new FileInputStream(file)

      // Get the bytes from the image file
      val byteArr = getBytesFromStream(fis)

      val options = new BitmapFactory.Options()

      if (!decodeAndCheck(byteArr, options)) {
        return null
      }

      options.inSampleSize = calculateInSampleSize(options, 200)
      options.inPreferredConfig = Bitmap.Config.RGB_565
      options.inJustDecodeBounds = false

      val bitmap = BitmapFactory.decodeByteArray(byteArr, 0, byteArr.length, options)

      if (isAvatar) {
        addAvatarToCache(imageKey, bitmap)
      } else {
        addBitmapToMemoryCache(imageKey, bitmap)
      }

      bitmap
    } catch {
      case e: FileNotFoundException =>
        AntoxLog.debug("File not found when trying to be used for FileInputStream", TAG)
        e.printStackTrace()
        null
    } finally {
      if (fis != null) {
        fis.close()
      }
    }
  }

  def load(file: File, isAvatar: Boolean): Observable[Bitmap] = {
    val imageKey = file.getPath + file.getName

    AntoxLog.debug(imageKey, TAG)

    Observable[Bitmap](sub => {
      sub.onNext(getFromCache(isAvatar, imageKey) match {
        case Some(bitmap) =>
          AntoxLog.debug("Loading Bitmap image from cache", TAG)
          bitmap

        case None =>
          AntoxLog.debug("Decoding Bitmap image", TAG)
          decodeBitmap(file, imageKey, isAvatar)
      })
      sub.onCompleted()
    }).subscribeOn(NewThreadScheduler())
      .observeOn(AndroidMainThreadScheduler())
  }
}

class BitmapManager {
  val maxMemory = (Runtime.getRuntime.maxMemory() / 1024).toInt
  val cacheSize = maxMemory / 8

  mMemoryCache = new LruCache[String, Bitmap](cacheSize) {
    // Measure size in KB instead of number of items
    protected override def sizeOf(key: String, bitmap: Bitmap): Int =
      bitmap.getSizeInBytes.asInstanceOf[Int] / 1024
  }
}
