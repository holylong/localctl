package com.minrray.voicectl.core.face

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

class FaceDBHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "facedb.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_FACES = "faces"

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_FEATURE = "feature"
        const val COLUMN_IMAGE = "face_image"
        const val COLUMN_ROLE = "role"
        const val COLUMN_CREATED_AT = "created_at"

        private const val CREATE_TABLE_FACES = """
            CREATE TABLE $TABLE_FACES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_FEATURE BLOB NOT NULL,
                $COLUMN_IMAGE BLOB,
                $COLUMN_ROLE INTEGER NOT NULL DEFAULT 1,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_FACES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_FACES ADD COLUMN $COLUMN_ROLE INTEGER NOT NULL DEFAULT 1")
        }
    }

    fun insertFace(name: String, feature: FloatArray, faceImage: Bitmap?, role: Int = 1): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_FEATURE, featureToBytes(feature))
            put(COLUMN_IMAGE, bitmapToBytes(faceImage))
            put(COLUMN_ROLE, role)
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return db.insert(TABLE_FACES, null, values)
    }

    fun getAllFaces(): List<FaceRecord> {
        val faces = mutableListOf<FaceRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FACES,
            null, null, null, null, null,
            "$COLUMN_CREATED_AT DESC"
        )

        cursor.use {
            if (it.moveToFirst()) {
                do {
                    faces.add(FaceRecord(
                        id = it.getInt(it.getColumnIndexOrThrow(COLUMN_ID)),
                        name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME)),
                        feature = bytesToFeature(it.getBlob(it.getColumnIndexOrThrow(COLUMN_FEATURE))),
                        imageBytes = it.getBlob(it.getColumnIndexOrThrow(COLUMN_IMAGE)),
                        role = it.getInt(it.getColumnIndexOrThrow(COLUMN_ROLE)),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT))
                    ))
                } while (it.moveToNext())
            }
        }
        return faces
    }

    fun getAllFaceFeatures(): List<Pair<String, FloatArray>> {
        val faces = mutableListOf<Pair<String, FloatArray>>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FACES,
            arrayOf(COLUMN_NAME, COLUMN_FEATURE),
            null, null, null, null, null
        )

        cursor.use {
            if (it.moveToFirst()) {
                do {
                    val name = it.getString(0)
                    val feature = bytesToFeature(it.getBlob(1))
                    faces.add(Pair(name, feature))
                } while (it.moveToNext())
            }
        }
        return faces
    }

    fun deleteFace(name: String): Int {
        val db = writableDatabase
        return db.delete(TABLE_FACES, "$COLUMN_NAME = ?", arrayOf(name))
    }

    fun deleteFaceById(id: Int): Int {
        val db = writableDatabase
        return db.delete(TABLE_FACES, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun clearAllFaces(): Int {
        val db = writableDatabase
        return db.delete(TABLE_FACES, null, null)
    }

    fun getFaceCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_FACES", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun hasRegisteredFaces(): Boolean {
        return getFaceCount() > 0
    }

    fun getFaceRole(name: String): Int? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FACES,
            arrayOf(COLUMN_ROLE),
            "$COLUMN_NAME = ?",
            arrayOf(name),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return null
    }

    fun updateFaceRole(name: String, role: Int): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_ROLE, role)
        }
        return db.update(TABLE_FACES, values, "$COLUMN_NAME = ?", arrayOf(name))
    }

    fun updateFace(name: String, feature: FloatArray, faceImage: Bitmap?, role: Int): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_FEATURE, featureToBytes(feature))
            put(COLUMN_IMAGE, bitmapToBytes(faceImage))
            put(COLUMN_ROLE, role)
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return db.update(TABLE_FACES, values, "$COLUMN_NAME = ?", arrayOf(name))
    }

    private fun featureToBytes(feature: FloatArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        dos.writeInt(feature.size)
        for (f in feature) {
            dos.writeFloat(f)
        }
        dos.flush()
        return baos.toByteArray()
    }

    private fun bytesToFeature(bytes: ByteArray): FloatArray {
        val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        val size = dis.readInt()
        val feature = FloatArray(size)
        for (i in 0 until size) {
            feature[i] = dis.readFloat()
        }
        return feature
    }

    private fun bitmapToBytes(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        return baos.toByteArray()
    }
}

data class FaceRecord(
    val id: Int,
    val name: String,
    val feature: FloatArray,
    val imageBytes: ByteArray?,
    val role: Int = 1,
    val createdAt: Long
) {
    fun getBitmap(): Bitmap? {
        return imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    fun isMaster(): Boolean {
        return role == 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceRecord

        if (id != other.id) return false
        if (name != other.name) return false
        if (!feature.contentEquals(other.feature)) return false
        if (role != other.role) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + name.hashCode()
        result = 31 * result + feature.contentHashCode()
        result = 31 * result + role
        return result
    }
}
