package com.yeshkupa.pos

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class Product(
    val id: Long,
    val name: String,
    val price: Double,
    val cost: Double,
    val stock: Int,
    val barcode: String,
    val category: String
)
private data class CartLine(val product: Product, var quantity: Int = 1)

class MainActivity : Activity() {
    private lateinit var store: PosStore
    private lateinit var printerManager: PrinterManager
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var printerStatus: TextView
    private val cart = mutableListOf<CartLine>()
    private val money = NumberFormat.getCurrencyInstance(Locale("he", "IL"))
    private var currentScreen = "pos"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PosStore(this)
        printerManager = PrinterManager(this) { message ->
            if (::printerStatus.isInitialized) printerStatus.text = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        buildShell()
        showScreen("pos")
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF3F5F8.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        header.addView(TextView(this).apply {
            text = "יש קופה"
            textSize = 25f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF152238.toInt())
        }, match())
        header.addView(TextView(this).apply {
            text = "מערכת מכירות וניהול עסק"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF667085.toInt())
        }, match())
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, dp(8), 4, dp(4))
        }
        listOf("קופה" to "pos", "מלאי" to "inventory", "דוחות" to "reports", "הגדרות" to "settings").forEach { (label, key) ->
            nav.addView(Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { showScreen(key) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        header.addView(nav, match())
        root.addView(header, match())
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showScreen(screen: String) {
        currentScreen = screen
        content.removeAllViews()
        when (screen) {
            "pos" -> showPos()
            "inventory" -> showInventory()
            "reports" -> showReports()
            "settings" -> showSettings()
        }
    }

    private fun showPos() {
        title(content, "קופה")
        card(content, "מכירות היום: ${money.format(store.todaySales())}")
        val search = EditText(this).apply {
            hint = "חיפוש לפי שם או סריקת ברקוד"
            textSize = 16f
            setSingleLine(true)
        }
        content.addView(search, match())
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, match())
        fun loadProducts(query: String) {
            results.removeAllViews()
            val found = store.products(query)
            if (found.isEmpty()) results.addView(label("לא נמצאו מוצרים. ניתן להוסיף אותם במסך מלאי."))
            found.forEach { p ->
                val row = row()
                row.addView(TextView(this).apply {
                    text = "${p.name}\n${money.format(p.price)} • מלאי: ${p.stock}"
                    textSize = 15f
                    setTextColor(0xFF101828.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                })
                row.addView(button("הוסף") {
                    if (p.stock <= 0) toast("המוצר אזל מהמלאי")
                    else {
                        val existing = cart.firstOrNull { it.product.id == p.id }
                        if (existing == null) cart.add(CartLine(p)) else if (existing.quantity < p.stock) existing.quantity++ else toast("אין מספיק מלאי")
                        showScreen("pos")
                    }
                })
                results.addView(row, match())
                results.addView(separator())
            }
        }
        search.addTextChangedListener(SimpleTextWatcher { loadProducts(search.text.toString()) })
        search.setOnEditorActionListener { _, _, _ ->
            val scanned = store.productByBarcode(search.text.toString().trim())
            if (scanned != null) {
                if (scanned.stock > 0) {
                    val existing = cart.firstOrNull { it.product.id == scanned.id }
                    if (existing == null) cart.add(CartLine(scanned)) else existing.quantity++
                    search.text.clear()
                    showScreen("pos")
                } else toast("המוצר אזל מהמלאי")
                true
            } else false
        }
        loadProducts("")
        section(content, "סל הקנייה")
        if (cart.isEmpty()) content.addView(label("הסל ריק"))
        cart.toList().forEach { line ->
            val r = row()
            r.addView(TextView(this).apply {
                text = "${line.product.name} × ${line.quantity}\n${money.format(line.product.price * line.quantity)}"
                textSize = 15f
                setTextColor(0xFF101828.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setPadding(dp(6), dp(8), dp(6), dp(8))
            })
            r.addView(button("−") { line.quantity--; if (line.quantity <= 0) cart.remove(line); showScreen("pos") })
            r.addView(button("+") {
                if (line.quantity < line.product.stock) line.quantity++ else toast("אין מספיק מלאי")
                showScreen("pos")
            })
            content.addView(r, match())
        }
        val total = cart.sumOf { it.product.price * it.quantity }
        card(content, "סה״כ לתשלום: ${money.format(total)}")
        content.addView(button("בצע מכירה ושמור") { checkout() }, match())
        content.addView(button("נקה סל") { cart.clear(); showScreen("pos") }, match())
    }

    private fun checkout() {
        if (cart.isEmpty()) { toast("הסל ריק"); return }
        val result = store.saveSale(cart)
        if (!result.first) { toast(result.second); return }
        val receipt = buildString {
            append("יש קופה\n")
            append("תאריך: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("he", "IL")).format(Date())}\n")
            append("------------------------\n")
            cart.forEach { append("${it.product.name} x${it.quantity}\n${money.format(it.product.price * it.quantity)}\n") }
            append("------------------------\n")
            append("סה״כ: ${money.format(cart.sumOf { it.product.price * it.quantity })}\n\n\n")
        }
        cart.clear()
        getSharedPreferences("pos_settings", MODE_PRIVATE).edit().putString("last_receipt", receipt).apply()
        showScreen("pos")
        toast("המכירה נשמרה בהצלחה")
        if (getSharedPreferences("pos_settings", MODE_PRIVATE).getBoolean("print_after_sale", false)) {
            printerManager.printReceipt(receipt)
        }
    }

    private fun showInventory() {
        title(content, "ניהול מלאי ומוצרים")
        content.addView(button("הוסף מוצר חדש") { productDialog(null) }, match())
        val search = EditText(this).apply { hint = "חיפוש לפי שם, ברקוד או קטגוריה"; setSingleLine(true) }
        content.addView(search, match())
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, match())
        fun refresh(query: String) {
            list.removeAllViews()
            val products = store.products(query)
            if (products.isEmpty()) list.addView(label("אין מוצרים. לחץ על הוסף מוצר חדש."))
            products.forEach { p ->
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(0xFFFFFFFF.toInt())
                }
                box.addView(TextView(this).apply {
                    text = p.name
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF101828.toInt())
                })
                box.addView(label("ברקוד: ${p.barcode.ifBlank { "לא הוגדר" }}  |  קטגוריה: ${p.category.ifBlank { "כללי" }}"))
                box.addView(label("מחיר: ${money.format(p.price)}  |  עלות: ${money.format(p.cost)}  |  רווח ליחידה: ${money.format(p.price-p.cost)}"))
                box.addView(label("כמות במלאי: ${p.stock}"))
                val actions = row()
                actions.addView(button("ערוך") { productDialog(p) })
                actions.addView(button("מחק") {
                    AlertDialog.Builder(this).setTitle("מחיקת מוצר")
                        .setMessage("למחוק את ${p.name}? היסטוריית המכירות תישמר.")
                        .setNegativeButton("ביטול", null)
                        .setPositiveButton("מחק") { _, _ -> store.deleteProduct(p.id); refresh(search.text.toString()) }.show()
                })
                box.addView(actions)
                list.addView(box, match())
                list.addView(separator())
            }
        }
        search.addTextChangedListener(SimpleTextWatcher { refresh(search.text.toString()) })
        refresh("")
    }

    private fun productDialog(product: Product?) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun field(hintText: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                hint = hintText
                setSingleLine(true)
                inputType = type
                setText(value)
            }.also { panel.addView(it, match()) }
        }
        val name = field("שם המוצר", product?.name ?: "")
        val barcode = field("ברקוד", product?.barcode ?: "", InputType.TYPE_CLASS_NUMBER)
        val category = field("קטגוריה", product?.category ?: "")
        val cost = field("עלות ליחידה בש״ח", product?.cost?.toString() ?: "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val price = field("מחיר מכירה בש״ח", product?.price?.toString() ?: "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val stock = field("כמות במלאי", product?.stock?.toString() ?: "0", InputType.TYPE_CLASS_NUMBER)
        val scroll = ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this)
            .setTitle(if (product == null) "הוספת מוצר" else "עריכת מוצר")
            .setView(scroll)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("שמור") { _, _ ->
                val n = name.text.toString().trim()
                val c = cost.text.toString().replace(',', '.').toDoubleOrNull()
                val p = price.text.toString().replace(',', '.').toDoubleOrNull()
                val q = stock.text.toString().toIntOrNull()
                if (n.isBlank() || c == null || p == null || q == null || c < 0 || p <= 0 || q < 0) {
                    toast("בדוק שם, עלות, מחיר וכמות")
                } else {
                    store.saveProduct(product?.id, n, p, c, q, barcode.text.toString().trim(), category.text.toString().trim())
                    showScreen("inventory")
                    toast("המוצר נשמר")
                }
            }.show()
    }

    private fun showReports() {
        title(content, "דוחות ומכירות")
        card(content, "מכירות היום: ${money.format(store.todaySales())}")
        card(content, "מכירות החודש: ${money.format(store.monthSales())}")
        card(content, "שווי מלאי לפי עלות: ${money.format(store.inventoryValue())}")
        card(content, "רווח גולמי משוער החודש: ${money.format(store.monthGrossProfit())}")
        section(content, "מכירות אחרונות")
        val sales = store.recentSales()
        if (sales.isEmpty()) content.addView(label("עדיין לא נשמרו מכירות"))
        sales.forEach { sale ->
            content.addView(label("${sale.second}  •  ${money.format(sale.third)}"))
            content.addView(separator())
        }
        content.addView(label("הרווח הגולמי מחושב לפי עלות המוצר השמורה בעת המכירה. הוא אינו כולל הוצאות עסק, מע״מ או עמלות."))
    }

    private fun showSettings() {
        title(content, "הגדרות")
        section(content, "מדפסות קבלות")
        printerStatus = TextView(this).apply {
            text = "בחר סריקה כדי לאתר התקני USB או מדפסות Bluetooth מותאמות"
            textSize = 14f
            setTextColor(0xFF475467.toInt())
            setPadding(0, dp(6), 0, dp(8))
        }
        content.addView(printerStatus, match())
        content.addView(button("סרוק מדפסות USB ו-Bluetooth") { printerManager.scan() }, match())
        content.addView(button("הדפס שוב את הקבלה האחרונה") {
            val receipt = getSharedPreferences("pos_settings", MODE_PRIVATE).getString("last_receipt", null)
            if (receipt.isNullOrBlank()) toast("אין קבלה אחרונה להדפסה")
            else printerManager.printReceipt(receipt)
        }, match())
        section(content, "אפשרויות קופה")
        val autoPrint = CheckBox(this).apply {
            text = "הדפסה אוטומטית לאחר מכירה (הגדרה בלבד)"
            isChecked = getSharedPreferences("pos_settings", MODE_PRIVATE).getBoolean("print_after_sale", false)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("pos_settings", MODE_PRIVATE).edit().putBoolean("print_after_sale", checked).apply()
            }
        }
        content.addView(autoPrint, match())
        section(content, "נתונים")
        content.addView(label("הנתונים נשמרים מקומית במכשיר. מומלץ לבצע גיבוי לפני שימוש קבוע."))
        content.addView(button("רענן מסך") { showScreen(currentScreen) }, match())
        content.addView(label("יש קופה • גרסת פיתוח. אינה מערכת חשבונאית מאושרת ואינה מפיקה מסמך מס חוקי."))
    }

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF152238.toInt())
            setPadding(0, dp(8), 0, dp(14))
        }, match())
    }
    private fun section(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1D2939.toInt())
            setPadding(0, dp(16), 0, dp(8))
        }, match())
    }
    private fun card(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF14532D.toInt())
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }, match())
    }
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF667085.toInt())
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setBackgroundColor(0xFFFFFFFF.toInt())
    }
    private fun separator() = View(this).apply {
        setBackgroundColor(0xFFE4E7EC.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }
    private fun match() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PrinterManager.REQUEST_BLUETOOTH && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            printerManager.scan()
        }
    }
}

private class PosStore(context: android.content.Context) :
    SQLiteOpenHelper(context, "yesh_kupa.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, price REAL NOT NULL, cost REAL NOT NULL DEFAULT 0, stock INTEGER NOT NULL DEFAULT 0, barcode TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE sales (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, total REAL NOT NULL, gross_profit REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE sale_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER NOT NULL, product_name TEXT NOT NULL, quantity INTEGER NOT NULL, unit_price REAL NOT NULL, unit_cost REAL NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE products ADD COLUMN cost REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE products ADD COLUMN stock INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE products ADD COLUMN barcode TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE products ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE sales ADD COLUMN gross_profit REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sale_lines ADD COLUMN unit_cost REAL NOT NULL DEFAULT 0")
        }
    }
    fun products(query: String): List<Product> {
        val out = mutableListOf<Product>()
        val where = if (query.isBlank()) null else "(name LIKE ? OR barcode LIKE ? OR category LIKE ?)"
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%", "%$query%")
        readableDatabase.query("products", arrayOf("id","name","price","cost","stock","barcode","category"), where, args, null, null, "name COLLATE NOCASE").use { c ->
            while (c.moveToNext()) out.add(Product(c.getLong(0),c.getString(1),c.getDouble(2),c.getDouble(3),c.getInt(4),c.getString(5),c.getString(6)))
        }
        return out
    }
    fun productByBarcode(code: String): Product? = products(code).firstOrNull { it.barcode == code }
    fun saveProduct(id: Long?, name: String, price: Double, cost: Double, stock: Int, barcode: String, category: String) {
        val values = ContentValues().apply {
            put("name",name); put("price",price); put("cost",cost); put("stock",stock); put("barcode",barcode); put("category",category)
        }
        if (id == null) writableDatabase.insertOrThrow("products", null, values)
        else writableDatabase.update("products", values, "id=?", arrayOf(id.toString()))
    }
    fun deleteProduct(id: Long) { writableDatabase.delete("products","id=?",arrayOf(id.toString())) }
    fun saveSale(lines: List<CartLine>): Pair<Boolean,String> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (line in lines) {
                db.rawQuery("SELECT stock FROM products WHERE id=?", arrayOf(line.product.id.toString())).use {
                    if (!it.moveToFirst() || it.getInt(0) < line.quantity) return false to "אין מספיק מלאי עבור ${line.product.name}"
                }
            }
            val total = lines.sumOf { it.product.price * it.quantity }
            val gross = lines.sumOf { (it.product.price - it.product.cost) * it.quantity }
            val saleId = db.insertOrThrow("sales", null, ContentValues().apply {
                put("created_at",System.currentTimeMillis()); put("total",total); put("gross_profit",gross)
            })
            lines.forEach { line ->
                db.insertOrThrow("sale_lines", null, ContentValues().apply {
                    put("sale_id",saleId); put("product_name",line.product.name); put("quantity",line.quantity)
                    put("unit_price",line.product.price); put("unit_cost",line.product.cost)
                })
                db.execSQL("UPDATE products SET stock=stock-? WHERE id=?", arrayOf(line.quantity,line.product.id))
            }
            db.setTransactionSuccessful()
            return true to "נשמר"
        } catch (e: Exception) {
            return false to (e.message ?: "שגיאה בשמירת המכירה")
        } finally { db.endTransaction() }
    }
    fun todaySales(): Double = sumSales("created_at >= ?", arrayOf(dayStart().toString()))
    fun monthSales(): Double = sumSales("created_at >= ?", arrayOf(monthStart().toString()))
    private fun sumSales(where: String, args: Array<String>): Double {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(total),0) FROM sales WHERE $where",args).use { return if(it.moveToFirst()) it.getDouble(0) else 0.0 }
    }
    fun monthGrossProfit(): Double {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(gross_profit),0) FROM sales WHERE created_at>=?", arrayOf(monthStart().toString())).use { return if(it.moveToFirst()) it.getDouble(0) else 0.0 }
    }
    fun inventoryValue(): Double {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(cost*stock),0) FROM products",null).use { return if(it.moveToFirst()) it.getDouble(0) else 0.0 }
    }
    fun recentSales(): List<Triple<Long,String,Double>> {
        val out= mutableListOf<Triple<Long,String,Double>>()
        readableDatabase.rawQuery("SELECT id,created_at,total FROM sales ORDER BY created_at DESC LIMIT 30",null).use { c ->
            while(c.moveToNext()) out.add(Triple(c.getLong(0),SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("he","IL")).format(Date(c.getLong(1))),c.getDouble(2)))
        }
        return out
    }
    private fun dayStart(): Long = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
    private fun monthStart(): Long = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH,1); set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
}
private class SimpleTextWatcher(private val changed: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
