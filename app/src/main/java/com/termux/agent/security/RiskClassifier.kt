package com.termux.agent.security

import com.termux.agent.model.RiskLevel
import com.termux.agent.model.StepResult

class RiskClassifier(private val policy: CommandPolicy = CommandPolicy()) {

    fun classify(program: String, args: List<String>): RiskLevel {
        val base = program.split("/").last()

        return when {
            base in policy.blocklist -> RiskLevel.HIGH
            base in policy.dangerousCommands -> {
                if (base == "rm" && args.any { it in listOf("-rf", "-fr", "--recursive", "-r", "-f") }) {
                    RiskLevel.HIGH
                } else if (base in listOf("curl", "wget", "nc", "netcat")) {
                    RiskLevel.HIGH
                } else {
                    RiskLevel.MEDIUM
                }
            }
            args.any { it.startsWith("/") && it.contains("..") } -> RiskLevel.HIGH
            args.joinToString(" ").contains("\$(") || args.joinToString(" ").contains('`') -> RiskLevel.HIGH
            else -> RiskLevel.LOW
        }
    }

    fun getConfirmationMessage(program: String, args: List<String>): String {
        val base = program.split("/").last()
        val risk = classify(program, args)
        val argStr = args.joinToString(" ")

        val riskLabel = when (risk) {
            RiskLevel.HIGH -> "高风险"
            RiskLevel.MEDIUM -> "中等风险"
            RiskLevel.LOW -> "低风险"
            RiskLevel.NONE -> "无风险"
        }

        return buildString {
            appendLine("需要确认操作")
            appendLine("命令: $base $argStr")
            appendLine("风险等级: $riskLabel")
            when (base) {
                "rm" -> appendLine("影响: 删除文件或目录")
                "apt-get", "apt" -> appendLine("影响: 安装或更新系统包")
                "curl", "wget" -> appendLine("影响: 从网络下载文件")
                "chmod", "chown" -> appendLine("影响: 修改文件权限")
                "pip", "pip3" -> appendLine("影响: 安装 Python 包")
            }
        }
    }
}
