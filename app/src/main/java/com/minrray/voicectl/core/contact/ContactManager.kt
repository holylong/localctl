package com.minrray.voicectl.core.contact

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 通讯录管理器，用于管理联系人信息和拨打电话功能
 */
class ContactManager private constructor() {
    companion object {
        private const val TAG = "ContactManager"
        private lateinit var instance: ContactManager
        private lateinit var context: Context
        
        // 权限常量
        private const val PERMISSION_READ_CONTACTS = android.Manifest.permission.READ_CONTACTS
        private const val PERMISSION_CALL_PHONE = android.Manifest.permission.CALL_PHONE
        private const val PERMISSION_REQUEST_CODE = 100
        
        fun initialize(appContext: Context) {
            context = appContext.applicationContext
            if (!::instance.isInitialized) {
                instance = ContactManager()
            }
        }
        
        fun getInstance(): ContactManager {
            if (!::instance.isInitialized) {
                throw IllegalStateException("ContactManager must be initialized first with initialize()")
            }
            return instance
        }
    }
    
    /**
     * 检查是否有读取联系人权限
     * @return 是否有读取联系人权限
     */
    fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            PERMISSION_READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查是否有拨打电话权限
     * @return 是否有拨打电话权限
     */
    fun hasCallPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            PERMISSION_CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 从系统通讯录查找联系人
     * @param name 联系人姓名、昵称或备注
     * @return 联系人电话号码，如果未找到或没有权限返回null
     */
    fun findContactByName(name: String): String? {
        Log.d(TAG, "从系统通讯录查找联系人: $name")
        
        // 检查权限
        if (!hasReadContactsPermission()) {
            Log.e(TAG, "没有读取联系人权限")
            return null
        }
        
        var bestMatchPhone: String? = null
        var bestMatchName: String? = null
        var bestMatchScore = 0
        
        try {
            // 1. 先尝试精确匹配，优先级最高
            var cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )
            
            cursor?.use { 
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    bestMatchPhone = it.getString(numberIndex)
                    bestMatchName = it.getString(nameIndex)
                    bestMatchScore = 100 // 精确匹配分数最高
                    Log.d(TAG, "精确匹配到联系人 $bestMatchName，电话号码: $bestMatchPhone")
                }
            }
            
            // 2. 如果没有精确匹配，尝试模糊匹配
            if (bestMatchScore < 100) {
                // 查询包含联系人的所有信息，包括备注、昵称等
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_ALTERNATIVE,
                    ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED,
                    ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED
                )
                
                // 使用更全面的匹配条件，包括昵称等
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                               "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_ALTERNATIVE} LIKE ?"
                val selectionArgs = arrayOf("%$name%", "%$name%")
                
                cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    // 按联系频率排序，让常用联系人优先
                    "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC, ${ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED} DESC"
                )
                
                cursor?.use { 
                    Log.d(TAG, "模糊匹配到 ${it.count} 个联系人，正在分析最佳匹配...")
                    
                    // 遍历所有匹配到的联系人，计算匹配分数
                    while (it.moveToNext()) {
                        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val altNameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_ALTERNATIVE)
                        val contactedTimesIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED)
                        
                        val phone = it.getString(numberIndex)
                        val displayName = it.getString(nameIndex)
                        val altName = it.getString(altNameIndex) ?: ""
                        val contactedTimes = it.getInt(contactedTimesIndex)
                        
                        // 计算匹配分数
                        var score = 0
                        
                        // 完全匹配分数最高
                        if (displayName == name || altName == name) {
                            score += 90
                        }
                        // 开头匹配次之
                        else if (displayName.startsWith(name) || altName.startsWith(name)) {
                            score += 80
                        }
                        // 包含匹配分数较低
                        else if (displayName.contains(name) || altName.contains(name)) {
                            score += 70
                        }
                        
                        // 联系频率加分
                        score += contactedTimes / 10 // 每联系10次加1分
                        
                        Log.d(TAG, "匹配到联系人: $displayName (备选名: $altName), 电话号码: $phone, 联系次数: $contactedTimes, 匹配分数: $score")
                        
                        // 更新最佳匹配
                        if (score > bestMatchScore) {
                            bestMatchScore = score
                            bestMatchPhone = phone
                            bestMatchName = displayName
                            Log.d(TAG, "更新最佳匹配: $bestMatchName, 分数: $bestMatchScore")
                        }
                    }
                }
            }
            
            // 3. 如果还是没有匹配结果，尝试查询联系人的结构化名称
            if (bestMatchScore == 0) {
                // 查询结构化名称
                val structuredNameProjection = arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
                )
                
                val structuredNameSelection = "${ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME} LIKE ? OR " +
                                              "${ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME} LIKE ? OR " +
                                              "${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} LIKE ?"
                val structuredNameArgs = arrayOf("%$name%", "%$name%", "%$name%")
                
                val structuredCursor = context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    structuredNameProjection,
                    "${ContactsContract.Data.MIMETYPE} = ? AND ($structuredNameSelection)",
                    arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) + structuredNameArgs,
                    null
                )
                
                structuredCursor?.use { 
                    Log.d(TAG, "结构化名称匹配到 ${it.count} 个联系人")
                    while (it.moveToNext()) {
                        val displayName = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME))
                        Log.d(TAG, "结构化名称匹配: $displayName")
                    }
                }
            }
            
            // 清理电话号码格式
            if (bestMatchPhone != null) {
                bestMatchPhone = bestMatchPhone?.replace(Regex("[^0-9+]"), "")
                Log.d(TAG, "最终匹配结果: 联系人 $bestMatchName，电话号码: $bestMatchPhone，匹配分数: $bestMatchScore")
            } else {
                Log.d(TAG, "未找到匹配的联系人: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询联系人失败: ${e.message}", e)
        }
        
        return bestMatchPhone
    }
    
    /**
     * 拨打电话
     * @param phoneNumber 电话号码
     * @return 拨打电话的结果
     */
    fun makeCall(phoneNumber: String): Boolean {
        Log.d(TAG, "准备拨打电话: $phoneNumber")
        
        // 检查权限
        if (!hasCallPhonePermission()) {
            Log.e(TAG, "没有拨打电话权限")
            return false
        }
        
        try {
            // 创建电话拨打意图
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$phoneNumber")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            context.startActivity(intent)
            Log.d(TAG, "成功发起电话呼叫: $phoneNumber")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "拨打电话失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 根据姓名呼叫联系人
     * @param name 联系人姓名
     * @return 呼叫结果
     */
    fun callPerson(name: String): CallResult {
        Log.d(TAG, "呼叫联系人: $name")
        
        // 检查读取联系人权限
        if (!hasReadContactsPermission()) {
            return CallResult(false, "没有读取联系人权限")
        }
        
        // 查找联系人
        val phoneNumber = findContactByName(name)
        if (phoneNumber == null) {
            Log.e(TAG, "未找到联系人: $name")
            return CallResult(false, "未找到联系人: $name")
        }
        
        // 检查拨打电话权限
        if (!hasCallPhonePermission()) {
            return CallResult(false, "没有拨打电话权限")
        }
        
        // 拨打电话
        val success = makeCall(phoneNumber)
        if (success) {
            return CallResult(true, "已成功呼叫 $name，电话号码: $phoneNumber")
        } else {
            return CallResult(false, "呼叫 $name 失败")
        }
    }
    
    /**
     * 电话呼叫结果数据类
     */
    data class CallResult(val success: Boolean, val message: String)
}