package com.yeshkupa.pos

import android.app.Activity
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.InputType
import java.text.NumberFormat
import java.util.Locale

private data class Product(val id: Long, val name: String, val price: Double)
private data class CartLine(val product: Product, var quantity: Int = 1)

class MainActivity : Activity() {
    private lateinit var store: PosStore
    private lateinit var productsBox: LinearLayout
    private lateinit var cartBox: LinearLayout
    private lateinit var summary: TextView
    private lateinit var nameInput: EditText
    private lateinit var priceInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var printerManager: PrinterManager
    private lateinit var printerStatus: TextView
    private val cart = mutableListOf<CartLine>()
    private val money = NumberFormat.getCurrencyInstance(Locale("he", "IL"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PosStore(this)
        printerManager = PrinterManager(this) { message -> if (::printerStatus.isInitialized) printerStatus.text = message }
        buildScreen()
        refresh()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }
        val scroll = ScrollView(this)
        scroll.addView(page)
        setContentView(scroll)

        page.addView(TextView(this).apply {
            text = "יש קופה"
            textSize = 27f
            setTextColor(0xFF152238.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, matchWidth())

        page.addView(TextView(this).apply {
            text = "קופה ומוצרים — גרסת MVP מקומית"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF667085.toInt())
        }, matchWidth())

        summary = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF14532D.toInt())
            setPadding(0, dp(16), 0, dp(12))
        }
        page.addView(summary)

        section(page, "מדפסת קבלות")
        printerStatus = TextView(this).apply {
            text = "לא בוצעה סריקה עדיין"
            textSize = 14f
            setTextColor(0xFF475467.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        page.addView(printerStatus, matchWidth())
        page.addView(actionButton("סרוק מדפסות USB ו-Bluetooth") { printerManager.scan() }, matchWidth())

        section(page, "הוספת מוצר")
        nameInput = EditText(this).apply {
            hint = "שם המוצר"
            setSingleLine(true)
        }
        priceInput = EditText(this).apply {
            hint = "מחיר מכירה בש״ח"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        page.addView(nameInput, matchWidth())
        page.addView(priceInput, matchWidth())
        page.addView(actionButton("שמור מוצר") { addProduct() }, matchWidth())

        section(page, "חיפוש ובחירת מוצר")
        searchInput = EditText(this).apply {
            hint = "חיפוש לפי שם מוצר"
            setSingleLine(true)
        }
        page.addView(searchInput, matchWidth())
        searchInput.addTextChangedListener(SimpleTextWatcher { refreshProducts() })
        productsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(productsBox, matchWidth())

        section(page, "סל הקנייה")
        cartBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(cartBox, matchWidth())
        page.addView(actionButton("בצע מכירה ושמור") { checkout() }, matchWidth())
        page.addView(actionButton("נקה סל") {
            cart.clear()
            refresh()
        }, matchWidth())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PrinterManager.REQUEST_BLUETOOTH) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                printerManager.scan()
            } else {
                if (::printerStatus.isInitialized) printerStatus.text = "הרשאת Bluetooth לא אושרה; סריקת USB עדיין זמינה."
            }
        }
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1D2939.toInt())
            setPadding(0, dp(20), 0, dp(8))
        }, matchWidth())
    }

    private fun addProduct() {
        val name = nameInput.text.toString().trim()
        val price = priceInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (name.isBlank() || price == null || price <= 0) {
            toast("יש להזין שם מוצר ומחיר תקין")
            return
        }
        store.addProduct(name, price)
        nameInput.text.clear()
        priceInput.text.clear()
        toast("המוצר נשמר")
        refresh()
    }

    private fun refresh() {
        val today = store.todaySales()
        summary.text = "מכירות היום: ${money.format(today)}"
        refreshProducts()
        refreshCart()
    }

    private fun refreshProducts() {
        if (!::productsBox.isInitialized) return
        productsBox.removeAllViews()
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        val products = store.products(query)
        if (products.isEmpty()) {
            productsBox.addView(label("אין מוצרים להצגה. הוסף מוצר חדש למעלה."))
        } else {
            products.forEach { product ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(0xFFFFFFFF.toInt())
                }
                val text = TextView(this).apply {
                    this.text = "${product.name}\n${money.format(product.price)}"
                    textSize = 16f
                    setTextColor(0xFF101828.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
                }
                line.addView(text)
                line.addView(actionButton("הוסף לסל") {
                    val existing = cart.firstOrNull { it.product.id == product.id }
                    if (existing == null) cart.add(CartLine(product)) else existing.quantity++
                    refreshCart()
                })
                productsBox.addView(line, matchWidth())
                productsBox.addView(divider())
            }
        }
    }

    private fun refreshCart() {
        if (!::cartBox.isInitialized) return
        cartBox.removeAllViews()
        if (cart.isEmpty()) {
            cartBox.addView(label("הסל ריק"))
            return
        }
        cart.forEach { item ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            line.addView(TextView(this).apply {
                text = "${item.product.name} × ${item.quantity}\n${money.format(item.product.price * item.quantity)}"
                textSize = 15f
                setTextColor(0xFF101828.toInt())
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            })
            line.addView(actionButton("−") {
                item.quantity--
                if (item.quantity <= 0) cart.remove(item)
                refreshCart()
            })
            line.addView(actionButton("+") {
                item.quantity++
                refreshCart()
            })
            cartBox.addView(line, matchWidth())
            cartBox.addView(divider())
        }
        val total = cart.sumOf { it.product.price * it.quantity }
        cartBox.addView(TextView(this).apply {
            text = "סה״כ לתשלום: ${money.format(total)}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF14532D.toInt())
            setPadding(0, dp(12), 0, dp(8))
        }, matchWidth())
    }

    private fun checkout() {
        if (cart.isEmpty()) {
            toast("הסל ריק")
            return
        }
        val total = cart.sumOf { it.product.price * it.quantity }
        store.saveSale(cart, total)
        cart.clear()
        refresh()
        toast("המכירה נשמרה: ${money.format(total)}")
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(0xFF667085.toInt())
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    private fun actionButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 14f
        setOnClickListener { action() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFE4E7EC.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}

private class PosStore(context: android.content.Context) :
    SQLiteOpenHelper(context, "yesh_kupa.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, price REAL NOT NULL)")
        db.execSQL("CREATE TABLE sales (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, total REAL NOT NULL)")
        db.execSQL("CREATE TABLE sale_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER NOT NULL, product_name TEXT NOT NULL, quantity INTEGER NOT NULL, unit_price REAL NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addProduct(name: String, price: Double) {
        writableDatabase.insertOrThrow("products", null, ContentValues().apply {
            put("name", name)
            put("price", price)
        })
    }

    fun products(query: String): List<Product> {
        val result = mutableListOf<Product>()
        val cursor = readableDatabase.query(
            "products", arrayOf("id", "name", "price"),
            if (query.isBlank()) null else "name LIKE ?",
            if (query.isBlank()) null else arrayOf("%$query%"),
            null, null, "name COLLATE NOCASE ASC"
        )
        cursor.use {
            while (it.moveToNext()) result.add(Product(it.getLong(0), it.getString(1), it.getDouble(2)))
        }
        return result
    }

    fun saveSale(lines: List<CartLine>, total: Double) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val saleId = db.insertOrThrow("sales", null, ContentValues().apply {
                put("created_at", System.currentTimeMillis())
                put("total", total)
            })
            lines.forEach { line ->
                db.insertOrThrow("sale_lines", null, ContentValues().apply {
                    put("sale_id", saleId)
                    put("product_name", line.product.name)
                    put("quantity", line.quantity)
                    put("unit_price", line.product.price)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun todaySales(): Double {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(total), 0) FROM sales WHERE created_at >= ?",
            arrayOf(start.toString())
        ).use { return if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }
}

private class SimpleTextWatcher(private val changed: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
