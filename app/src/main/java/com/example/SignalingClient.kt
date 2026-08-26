package com.example

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

class SignalingClient(
    private val roomId: String,
    private val isHost: Boolean,
    private val listener: SignalingListener
) {
    private val db = FirebaseDatabase.getInstance().getReference("rooms").child(roomId)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        listenForSignaling()
    }

    private fun listenForSignaling() {
        if (isHost) {
            // Host listens for Viewer's answer and ICE candidates
            db.child("answer").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val type = snapshot.child("type").value.toString()
                        val sdp = snapshot.child("sdp").value.toString()
                        listener.onAnswerReceived(SessionDescription(SessionDescription.Type.valueOf(type), sdp))
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })

            db.child("viewer_candidates").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        val sdpMid = child.child("sdpMid").value.toString()
                        val sdpMLineIndex = child.child("sdpMLineIndex").value.toString().toIntOrNull() ?: 0
                        val sdp = child.child("sdp").value.toString()
                        listener.onIceCandidateReceived(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        } else {
            // Viewer listens for Host's offer and ICE candidates
            db.child("offer").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val type = snapshot.child("type").value.toString()
                        val sdp = snapshot.child("sdp").value.toString()
                        listener.onOfferReceived(SessionDescription(SessionDescription.Type.valueOf(type), sdp))
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })

            db.child("host_candidates").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        val sdpMid = child.child("sdpMid").value.toString()
                        val sdpMLineIndex = child.child("sdpMLineIndex").value.toString().toIntOrNull() ?: 0
                        val sdp = child.child("sdp").value.toString()
                        listener.onIceCandidateReceived(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    fun sendOffer(sessionDescription: SessionDescription) {
        val data = hashMapOf(
            "type" to sessionDescription.type.name,
            "sdp" to sessionDescription.description
        )
        db.child("offer").setValue(data)
    }

    fun sendAnswer(sessionDescription: SessionDescription) {
        val data = hashMapOf(
            "type" to sessionDescription.type.name,
            "sdp" to sessionDescription.description
        )
        db.child("answer").setValue(data)
    }

    fun sendIceCandidate(iceCandidate: IceCandidate) {
        val data = hashMapOf(
            "sdpMid" to iceCandidate.sdpMid,
            "sdpMLineIndex" to iceCandidate.sdpMLineIndex,
            "sdp" to iceCandidate.sdp
        )
        val node = if (isHost) "host_candidates" else "viewer_candidates"
        db.child(node).push().setValue(data)
    }
    
    fun cleanUp() {
        if (isHost) {
            db.removeValue() // Host deletes room on exit
        }
    }

    interface SignalingListener {
        fun onOfferReceived(sessionDescription: SessionDescription)
        fun onAnswerReceived(sessionDescription: SessionDescription)
        fun onIceCandidateReceived(iceCandidate: IceCandidate)
    }
}
