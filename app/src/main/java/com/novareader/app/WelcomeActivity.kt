package com.novareader.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран приветствия — показывается вместо MainActivity, когда на
 * устройстве нет ни текущей книги, ни книг в библиотеке (чистая установка
 * или переустановка с потерей данных). Раньше в этом случае MainActivity
 * молча грузила пустой reader.html — пользователь видел чёрный экран без
 * единой возможности добавить книгу.
 *
 * Единственное действие здесь — кнопка "Библиотека", которая открывает
 * LibraryActivity, где уже есть импорт файла. После выбора/импорта книги
 * экран закрывается и передаёт управление в MainActivity с этой книгой.
 */
class WelcomeActivity : AppCompatActivity() {

    private val openLibraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val relativePath = data?.getStringExtra(LibraryActivity.EXTRA_RELATIVE_PATH)
                if (relativePath != null) {
                    val displayName = data.getStringExtra(LibraryActivity.EXTRA_DISPLAY_NAME) ?: relativePath
                    val format = data.getStringExtra(LibraryActivity.EXTRA_FORMAT) ?: "unknown"
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            putExtra(LibraryActivity.EXTRA_RELATIVE_PATH, relativePath)
                            putExtra(LibraryActivity.EXTRA_DISPLAY_NAME, displayName)
                            putExtra(LibraryActivity.EXTRA_FORMAT, format)
                        }
                    )
                    finish()
                }
            }
            // "Отмена"/назад без выбора книги — остаёмся на этом экране,
            // пользователь может нажать "Библиотека" ещё раз.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        findViewById<Button>(R.id.btnGoToLibrary).setOnClickListener {
            openLibraryLauncher.launch(Intent(this, LibraryActivity::class.java))
        }
    }
}
