package com.subtitleedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.ActivityModelSettingsBinding
import com.subtitleedit.util.SettingsManager

/**
 * 模型设置页面
 */
class ModelSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelSettingsBinding
    private lateinit var settingsManager: SettingsManager

    private var encoderPath: String = ""
    private var decoderPath: String = ""
    private var tokensPath: String = ""
    private var vadModelPath: String = ""
    private var updatingVadThreshold = false

    private companion object {
        private const val VAD_THRESHOLD_MIN = 0.1f
        private const val VAD_THRESHOLD_MAX = 0.9f
        private const val VAD_THRESHOLD_STEP = 0.05f
    }

    // Encoder 文件选择器
    private val encoderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedEncoder(it) }
    }

    // Decoder 文件选择器
    private val decoderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedDecoder(it) }
    }

    // Tokens 文件选择器
    private val tokensPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedTokens(it) }
    }

    // VAD 模型文件选择器
    private val vadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedVad(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager.getInstance(this)

        setupToolbar()
        setupButtons()
        setupVadSettings()
        loadSavedSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "模型设置"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupButtons() {
        binding.btnSelectEncoder.setOnClickListener {
            encoderPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSelectDecoder.setOnClickListener {
            decoderPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSelectTokens.setOnClickListener {
            tokensPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSelectVad.setOnClickListener {
            vadPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.cbUseBuiltInVad.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setVadUseBuiltInModel(isChecked)
            updateVadModelUi()
        }

        binding.btnSpeechAdvancedSettings.setOnClickListener {
            startActivity(Intent(this, SpeechToSubtitleSettingsActivity::class.java))
        }

        binding.tvModelGuide.setOnClickListener {
            showModelGuide()
        }
    }

    private fun setupVadSettings() {
        binding.sliderVadThreshold.valueFrom = VAD_THRESHOLD_MIN
        binding.sliderVadThreshold.valueTo = VAD_THRESHOLD_MAX
        binding.sliderVadThreshold.stepSize = VAD_THRESHOLD_STEP
        binding.sliderVadThreshold.setLabelFormatter { value ->
            String.format("%.2f", normalizeVadThreshold(value))
        }

        // VAD 阈值
        binding.sliderVadThreshold.addOnChangeListener { _, value, fromUser ->
            if (updatingVadThreshold) return@addOnChangeListener
            val snapped = normalizeVadThreshold(value)
            if (fromUser) {
                updatingVadThreshold = true
                if (!floatEquals(binding.sliderVadThreshold.value, snapped)) {
                    binding.sliderVadThreshold.value = snapped
                }
                binding.etVadThreshold.setText(String.format("%.2f", snapped))
                binding.etVadThreshold.setSelection(binding.etVadThreshold.text?.length ?: 0)
                updatingVadThreshold = false
            }
            settingsManager.setVadThreshold(snapped)
        }
        binding.etVadThreshold.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingVadThreshold) return
                val text = s.toString()
                if (text.isBlank() || text.endsWith(".")) return
                val v = text.toFloatOrNull() ?: return
                val clamped = v.coerceIn(VAD_THRESHOLD_MIN, VAD_THRESHOLD_MAX)
                val snapped = normalizeVadThreshold(clamped)
                val normalized = String.format("%.2f", snapped)
                val decimalLength = text.substringAfter('.', "").takeIf { text.contains('.') }?.length ?: 0
                val shouldNormalizeText = decimalLength >= 2 || text.toFloatOrNull() != clamped
                updatingVadThreshold = true
                if (!floatEquals(binding.sliderVadThreshold.value, snapped)) {
                    binding.sliderVadThreshold.value = snapped
                }
                if (shouldNormalizeText && text != normalized) {
                    binding.etVadThreshold.setText(normalized)
                    binding.etVadThreshold.setSelection(binding.etVadThreshold.text?.length ?: 0)
                }
                updatingVadThreshold = false
                settingsManager.setVadThreshold(snapped)
            }
        })

        // 最小静音时长
        binding.sliderMinSilence.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etMinSilence.setText(String.format("%.2f", value))
            settingsManager.setVadMinSilenceDuration(value)
        }
        binding.etMinSilence.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toFloatOrNull() ?: return
                val clamped = v.coerceIn(0.1f, 2.0f)
                val snapped = (Math.round(clamped / 0.1f) * 0.1f)
                if (binding.sliderMinSilence.value != snapped) binding.sliderMinSilence.value = snapped
                settingsManager.setVadMinSilenceDuration(clamped)
            }
        })

        // 最小语音时长
        binding.sliderMinSpeech.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etMinSpeech.setText(String.format("%.2f", value))
            settingsManager.setVadMinSpeechDuration(value)
        }
        binding.etMinSpeech.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toFloatOrNull() ?: return
                val clamped = v.coerceIn(0.05f, 1.0f)
                val snapped = (Math.round(clamped / 0.05f) * 0.05f)
                if (binding.sliderMinSpeech.value != snapped) binding.sliderMinSpeech.value = snapped
                settingsManager.setVadMinSpeechDuration(clamped)
            }
        })

        // 最大语音时长
        binding.sliderMaxSpeech.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.etMaxSpeech.setText(String.format("%.1f", value))
            settingsManager.setVadMaxSpeechDuration(value)
        }
        binding.etMaxSpeech.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().toFloatOrNull() ?: return
                val clamped = v.coerceIn(5.0f, 60.0f)
                val snapped = (Math.round(clamped / 5.0f) * 5.0f).toFloat()
                if (binding.sliderMaxSpeech.value != snapped) binding.sliderMaxSpeech.value = snapped
                settingsManager.setVadMaxSpeechDuration(clamped)
            }
        })
    }

    private fun loadSavedSettings() {
        // 加载模型路径
        encoderPath = settingsManager.getWhisperEncoderPath()
        decoderPath = settingsManager.getWhisperDecoderPath()
        tokensPath = settingsManager.getWhisperTokensPath()
        vadModelPath = settingsManager.getVadModelPath()
        binding.cbUseBuiltInVad.isChecked = settingsManager.isVadUseBuiltInModel()

        if (encoderPath.isNotEmpty()) {
            val uri = Uri.parse(encoderPath)
            binding.tvEncoderFile.text = getFileNameFromUri(uri)
        }
        if (decoderPath.isNotEmpty()) {
            val uri = Uri.parse(decoderPath)
            binding.tvDecoderFile.text = getFileNameFromUri(uri)
        }
        if (tokensPath.isNotEmpty()) {
            val uri = Uri.parse(tokensPath)
            binding.tvTokensFile.text = getFileNameFromUri(uri)
        }
        updateVadModelUi()

        // 加载 VAD 参数
        val threshold = settingsManager.getVadThreshold()
        val minSilence = settingsManager.getVadMinSilenceDuration()
        val minSpeech = settingsManager.getVadMinSpeechDuration()
        val maxSpeech = settingsManager.getVadMaxSpeechDuration()

        settingsManager.setVadThreshold(threshold)
        binding.sliderVadThreshold.value = threshold
        binding.etVadThreshold.setText(String.format("%.2f", threshold))

        binding.sliderMinSilence.value = minSilence
        binding.etMinSilence.setText(String.format("%.2f", minSilence))

        binding.sliderMinSpeech.value = minSpeech
        binding.etMinSpeech.setText(String.format("%.2f", minSpeech))

        binding.sliderMaxSpeech.value = maxSpeech
        binding.etMaxSpeech.setText(String.format("%.1f", maxSpeech))
    }

    private fun handleSelectedEncoder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("encoder", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请选择 encoder 模型文件（文件名应包含 'encoder' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            encoderPath = uri.toString()
            settingsManager.setWhisperEncoderPath(encoderPath)
            binding.tvEncoderFile.text = fileName

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedDecoder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("decoder", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请选择 decoder 模型文件（文件名应包含 'decoder' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            decoderPath = uri.toString()
            settingsManager.setWhisperDecoderPath(decoderPath)
            binding.tvDecoderFile.text = fileName

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedTokens(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("token", ignoreCase = true) ||
                !fileName.endsWith(".txt", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请选择 tokens 文件（文件名应包含 'token' 且以 .txt 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            tokensPath = uri.toString()
            settingsManager.setWhisperTokensPath(tokensPath)
            binding.tvTokensFile.text = fileName

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedVad(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = getFileNameFromUri(uri)

            if (!fileName.contains("vad", ignoreCase = true) ||
                !fileName.endsWith(".onnx", ignoreCase = true)) {
                com.subtitleedit.util.OverwritingToast.makeText(
                    this,
                    "请选择 VAD 模型文件（文件名应包含 'vad' 且以 .onnx 结尾）",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            vadModelPath = uri.toString()
            settingsManager.setVadModelPath(vadModelPath)
            settingsManager.setVadUseBuiltInModel(false)
            binding.cbUseBuiltInVad.isChecked = false
            updateVadModelUi()
            com.subtitleedit.util.OverwritingToast.makeText(this, "外部 VAD 模型已选择", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            com.subtitleedit.util.OverwritingToast.makeText(this, "选择文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateVadModelUi() {
        val useBuiltIn = settingsManager.isVadUseBuiltInModel()
        binding.btnSelectVad.isEnabled = !useBuiltIn
        binding.btnSelectVad.alpha = if (useBuiltIn) 0.6f else 1f
        binding.tvVadFile.text = when {
            useBuiltIn -> "当前使用：内置 silero_vad.onnx"
            vadModelPath.isNotBlank() -> "当前使用：外部模型 ${getFileNameFromUri(Uri.parse(vadModelPath))}"
            else -> "当前使用：外部模型（未选择）"
        }
    }

    private fun normalizeVadThreshold(threshold: Float): Float {
        val clamped = threshold.coerceIn(VAD_THRESHOLD_MIN, VAD_THRESHOLD_MAX)
        val steps = Math.round((clamped - VAD_THRESHOLD_MIN) / VAD_THRESHOLD_STEP).coerceIn(0, 16)
        return ((VAD_THRESHOLD_MIN * 100).toInt() + steps * 5) / 100f
    }

    private fun floatEquals(a: Float, b: Float): Boolean {
        return kotlin.math.abs(a - b) < 0.0001f
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = "未知文件"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) {
                fileName = it.getString(nameIndex)
            }
        }
        return fileName
    }

    private fun showModelGuide() {
        val message = """
            Whisper 模型下载指引：

            1. 访问 GitHub 下载页面：
               https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models

            2. 下载模型文件（需要以下 3 个文件）：
               • encoder.onnx（或 large-v3-encoder.onnx）
               • decoder.onnx（或 large-v3-decoder.int8.onnx）
               • tokens.txt

            3. 分别点击"选择 Encoder"、"选择 Decoder"、"选择 Tokens"按钮选择对应文件

            推荐模型：
            • Whisper Tiny (~40MB) - 快速，适合实时
            • Whisper Base (~75MB) - 平衡性能和质量
            • Whisper Small (~250MB) - 高质量
            • Whisper Large V3 (~3GB) - 最高质量

            VAD 模型（可选，已内置）：
            • 项目已内置 silero_vad.onnx 模型
            • 如需使用外部模型，可从上述链接下载
            • 作用：精确检测语音段，提高字幕时间轴准确性
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("模型下载指引")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .setNeutralButton("打开 GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models"))
                startActivity(intent)
            }
            .show()
    }
}
