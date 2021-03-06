package chat.tox.antox.callbacks

import android.content.Context
import android.util.Log
import chat.tox.antox.data.State
import chat.tox.antox.tox.{MessageHelper, ToxSingleton}
import chat.tox.antox.utils.AntoxLog
import chat.tox.antox.wrapper.ToxKey

object AntoxOnGroupInviteCallback {

}

class AntoxOnGroupInviteCallback(private var ctx: Context) /* extends GroupInviteCallback */ {

  def groupInvite(friendNumber: Int, inviteData: Array[Byte]): Unit = {
    val db = State.db
    val inviter = ToxSingleton.getAntoxFriend(friendNumber).get
    if (db.isContactBlocked(inviter.key)) return

    val inviteKeyLength = 32
    val key = new ToxKey(inviteData.slice(0, inviteKeyLength))
    db.addGroupInvite(key, inviter.name, inviteData)

    AntoxLog.debug("New Group Invite")
    MessageHelper.createRequestNotification(None, ctx)
  }
}
