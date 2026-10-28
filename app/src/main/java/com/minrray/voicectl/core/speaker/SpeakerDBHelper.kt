package com.minrray.voicectl.core.speaker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class SpeakerDBHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "speakerdb.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_SPEAKERS = "speakers"

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_EMBEDDINGS = "embeddings"
        const val COLUMN_CREATED_AT = "created_at"

        private const val CREATE_TABLE_SPEAKERS = """
            CREATE TABLE $TABLE_SPEAKERS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL UNIQUE,
                $COLUMN_EMBEDDINGS BLOB NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_SPEAKERS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    fun insertSpeaker(name: String, embeddings: List<FloatArray>): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_EMBEDDINGS, embeddingsToBytes(embeddings))
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return db.insertWithOnConflict(TABLE_SPEAKERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllSpeakers(): List<SpeakerRecord> {
        val speakers = mutableListOf<SpeakerRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SPEAKERS,
            null, null, null, null, null,
            "$COLUMN_CREATED_AT DESC"
        )

        cursor.use {
            if (it.moveToFirst()) {
                do {
                    speakers.add(SpeakerRecord(
                        id = it.getInt(it.getColumnIndexOrThrow(COLUMN_ID)),
                        name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME)),
                        embeddings = bytesToEmbeddings(it.getBlob(it.getColumnIndexOrThrow(COLUMN_EMBEDDINGS))),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT))
                    ))
                } while (it.moveToNext())
            }
        }
        return speakers
    }

    fun getAllSpeakerEmbeddings(): List<Pair<String, Array<FloatArray>>> {
        val speakers = mutableListOf<Pair<String, Array<FloatArray>>>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SPEAKERS,
            arrayOf(COLUMN_NAME, COLUMN_EMBEDDINGS),
            null, null, null, null, null
        )

        cursor.use {
            if (it.moveToFirst()) {
                do {
                    val name = it.getString(0)
                    val embeddings = bytesToEmbeddings(it.getBlob(1))
                    speakers.add(Pair(name, embeddings))
                } while (it.moveToNext())
            }
        }
        return speakers
    }

    fun getSpeaker(name: String): SpeakerRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SPEAKERS,
            null,
            "$COLUMN_NAME = ?",
            arrayOf(name),
            null, null, null
        )

        cursor.use {
            if (it.moveToFirst()) {
                return SpeakerRecord(
                    id = it.getInt(it.getColumnIndexOrThrow(COLUMN_ID)),
                    name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME)),
                    embeddings = bytesToEmbeddings(it.getBlob(it.getColumnIndexOrThrow(COLUMN_EMBEDDINGS))),
                    createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT))
                )
            }
        }
        return null
    }

    fun deleteSpeaker(name: String): Int {
        val db = writableDatabase
        return db.delete(TABLE_SPEAKERS, "$COLUMN_NAME = ?", arrayOf(name))
    }

    fun deleteSpeakerById(id: Int): Int {
        val db = writableDatabase
        return db.delete(TABLE_SPEAKERS, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun clearAllSpeakers(): Int {
        val db = writableDatabase
        return db.delete(TABLE_SPEAKERS, null, null)
    }

    fun getSpeakerCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SPEAKERS", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun hasRegisteredSpeakers(): Boolean {
        return getSpeakerCount() > 0
    }

    private fun embeddingsToBytes(embeddings: List<FloatArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(embeddings.size)
        for (embedding in embeddings) {
            dos.writeInt(embedding.size)
            for (f in embedding) {
                dos.writeFloat(f)
            }
        }
        dos.flush()
        return baos.toByteArray()
    }

    private fun bytesToEmbeddings(bytes: ByteArray): Array<FloatArray> {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val count = dis.readInt()
        val embeddings = mutableListOf<FloatArray>()
        for (i in 0 until count) {
            val size = dis.readInt()
            val embedding = FloatArray(size)
            for (j in 0 until size) {
                embedding[j] = dis.readFloat()
            }
            embeddings.add(embedding)
        }
        return embeddings.toTypedArray()
    }
}

data class SpeakerRecord(
    val id: Int,
    val name: String,
    val embeddings: Array<FloatArray>,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpeakerRecord

        if (id != other.id) return false
        if (name != other.name) return false
        if (!embeddings.contentDeepEquals(other.embeddings)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + name.hashCode()
        result = 31 * result + embeddings.contentDeepHashCode()
        return result
    }
}
