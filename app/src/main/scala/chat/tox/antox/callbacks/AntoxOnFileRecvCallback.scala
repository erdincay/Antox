package chat.tox.antox.callbacks

import android.content.Context
import chat.tox.antox.data.State
import chat.tox.antox.tox.ToxSingleton
import chat.tox.antox.utils.Constants
import chat.tox.antox.wrapper.FileKind
import chat.tox.antox.wrapper.FileKind.AVATAR
import im.tox.tox4j.core.callbacks.FileRecvCallback
import im.tox.tox4j.core.enums.ToxFileControl

class AntoxOnFileRecvCallback(ctx: Context) extends FileRecvCallback[Unit] {
  override def fileRecv(friendNumber: Int,
                        fileNumber: Int,
                        toxFileKind: Int,
                        fileSize: Long,
                        filename: Array[Byte])(state: Unit): Unit = {
    val kind: FileKind = FileKind.fromToxFileKind(toxFileKind)
    val friend = ToxSingleton.getAntoxFriend(friendNumber).get

    val name =
      if (kind == FileKind.AVATAR) {
        friend.key.toString
      } else {
        new String(filename)
      }

    if (kind == FileKind.AVATAR) {
      if (fileSize > Constants.MAX_AVATAR_SIZE){
        return
      } else if (fileSize == 0) {
        ToxSingleton.tox.fileControl(friendNumber, fileNumber, ToxFileControl.CANCEL)
        ToxSingleton.getAntoxFriend(friendNumber).get.deleteAvatar()
        val db = State.db
        db.updateFriendAvatar(friend.key, "")
        return
      }

      val fileId = ToxSingleton.tox.fileGetFileId(friendNumber, fileNumber).toString
      val avatarFile = AVATAR.getAvatarFile(name, ctx).orNull

      if (avatarFile != null) {
        val storedFileId = ToxSingleton.tox.hash(avatarFile).orNull
        if (fileId.equals(storedFileId)) {
          ToxSingleton.tox.fileControl(friendNumber, fileNumber, ToxFileControl.CANCEL)
          return
        }
      }
    }

    val chatActive = State.isChatActive(friend.key)
    State.transfers.fileIncomingRequest(friend.key, friend.name, chatActive, fileNumber, name, kind, fileSize, kind.replaceExisting, ctx)

    if (kind.autoAccept) State.transfers.acceptFile(friend.key, fileNumber, ctx)
  }
}
