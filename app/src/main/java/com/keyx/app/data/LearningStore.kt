package com.keyx.app.data

import android.util.Log
import com.keyx.app.predict.LearnedModel
import org.json.JSONObject
import java.io.File

/** The learned model on disk: one file, sealed with a Keystore key. */
class LearningStore(dir: File, private val key: KeystoreKey = KeystoreKey("keyx_learning")) {
    private val file = File(dir, "learned.sealed")
    private val sealer = Sealer { key.get() }

    fun load(): LearnedModel {
        if (!file.exists()) return LearnedModel()
        return try {
            LearnedModel.fromJson(JSONObject(sealer.open(file.readBytes()).decodeToString()))
        } catch (e: Exception) {
            // A file the key cannot open (restored from elsewhere, key wiped) is
            // unreadable by design; start fresh rather than crash the keyboard.
            Log.w(TAG, "learned state unreadable, starting fresh: ${e.javaClass.simpleName}")
            LearnedModel()
        }
    }

    fun save(json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealer.seal(json.toByteArray()))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Reset: the file and the key that could open any copy of it. */
    fun wipe() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        key.delete()
    }

    val exists: Boolean get() = file.exists()

    private companion object {
        const val TAG = "KeyX"
    }
}
