package com.k2fsa.sherpa.onnx

class SpeakerEmbeddingManager(val dim: Int) {
    private var ptr: Long

    init {
        ptr = create(dim)
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()
    fun add(name: String, embedding: FloatArray) = add(ptr, name, embedding)
    fun add(name: String, embedding: Array<FloatArray>) = addList(ptr, name, embedding)
    fun remove(name: String) = remove(ptr, name)
    fun search(embedding: FloatArray, threshold: Float) = search(ptr, embedding, threshold)
    fun searchWithScore(embedding: FloatArray, threshold: Float) = searchWithScore(ptr, embedding, threshold)
    fun verify(name: String, embedding: FloatArray, threshold: Float) =
        verify(ptr, name, embedding, threshold)

    fun contains(name: String) = contains(ptr, name)
    fun numSpeakers() = numSpeakers(ptr)

    fun allSpeakerNames() = allSpeakerNames(ptr)

    private external fun create(dim: Int): Long
    private external fun delete(ptr: Long): Unit
    private external fun add(ptr: Long, name: String, embedding: FloatArray): Boolean
    private external fun addList(ptr: Long, name: String, embedding: Array<FloatArray>): Boolean
    private external fun remove(ptr: Long, name: String): Boolean
    private external fun search(ptr: Long, embedding: FloatArray, threshold: Float): String
    private external fun searchWithScore(ptr: Long, embedding: FloatArray, threshold: Float): FloatArray
    private external fun verify(
        ptr: Long,
        name: String,
        embedding: FloatArray,
        threshold: Float
    ): Boolean

    private external fun contains(ptr: Long, name: String): Boolean
    private external fun numSpeakers(ptr: Long): Int

    private external fun allSpeakerNames(ptr: Long): Array<String>
}
