package com.water.widget

import org.json.JSONArray
import org.json.JSONObject

enum class TaskPlatform { MAIN, APP }

data class PlannedMission(
    val refId: String,
    val name: String,
    val score: Int,
    val limit: Int,
    val platform: TaskPlatform
)

data class MissionWorkItem(
    val refId: String,
    val name: String,
    val score: Int,
    val round: Int,
    val total: Int,
    val platform: TaskPlatform
)

data class TaskScoreProgress(val completed: Map<String, Int>, val totalScore: Int?)

class TaskPointsTracker(private val before: Int?) {
    var total: Int = 0
        private set

    fun record(after: Int?): Int? {
        val increase = TaskMissionPlanner.verifiedIncrease(before, after) ?: return null
        if (increase < total) return null
        val added = increase - total
        total = increase
        return added
    }
}

object TaskMissionPlanner {
    fun progress(data: JSONObject): TaskScoreProgress {
        val score = requireNotNull(data.optJSONObject("accScoreRsp")) { "缺少账户积分进度" }
        val limits = if (!score.has("limits") || score.isNull("limits")) JSONArray()
            else requireNotNull(score.optJSONArray("limits")) { "任务次数格式错误" }
        val completed = buildMap {
            for (index in 0 until limits.length()) {
                val limit = requireNotNull(limits.optJSONObject(index)) { "任务次数条目格式错误" }
                val refId = limit.optString("refId")
                require(refId.isNotBlank()) { "任务次数缺少 refId" }
                val count = requireNotNull(limit.opt("limit")?.toString()?.toIntOrNull()) { "任务次数缺失" }
                require(count >= -1) { "任务次数无效" }
                put(refId, if (count == -1) Int.MAX_VALUE else count)
            }
        }
        return TaskScoreProgress(completed, score.opt("totalScore")?.toString()?.toIntOrNull()?.takeIf { it >= 0 })
    }

    fun verifiedIncrease(before: Int?, after: Int?): Int? =
        if (before == null || after == null || after < before) null else after - before

    fun merge(mainMissions: JSONArray?, appMissions: JSONArray?): List<PlannedMission> =
        (readMissions(mainMissions, TaskPlatform.MAIN) + readMissions(appMissions, TaskPlatform.APP))
            .distinctBy(PlannedMission::refId)

    private fun readMissions(missions: JSONArray?, platform: TaskPlatform): List<PlannedMission> = buildList {
        if (missions == null) return@buildList
        for (index in 0 until missions.length()) {
            val mission = missions.optJSONObject(index) ?: continue
            val refId = mission.optString("refId")
            if (refId.isBlank()) continue
            add(PlannedMission(
                refId = refId,
                name = mission.optString("name", mission.optString("title", "任务")),
                score = mission.optInt("score", 0),
                limit = mission.optInt("limit", 0),
                platform = platform
            ))
        }
    }

    fun diagnostics(
        mainMissions: JSONArray?,
        appMissions: JSONArray?,
        mainCounts: Map<String, Int>,
        appCounts: Map<String, Int>
    ): List<String> = buildList {
        val main = readMissions(mainMissions, TaskPlatform.MAIN).distinctBy(PlannedMission::refId)
        val app = readMissions(appMissions, TaskPlatform.APP).distinctBy(PlannedMission::refId)
        if (mainMissions != null) {
            val eligible = main.count { remaining(it, mainCounts) > 0 }
            add("支付宝任务列表：收到 ${mainMissions.length()} 项，识别 ${main.size} 项，按本端进度可排 $eligible 项")
        }
        if (appMissions != null) {
            val eligible = app.count { remaining(it, appCounts) > 0 }
            add("App 任务列表：收到 ${appMissions.length()} 项，识别 ${app.size} 项，按本端进度可排 $eligible 项")
        }
        val mainById = main.associateBy(PlannedMission::refId)
        val blocked = app.filter { mission ->
            val mainMission = mainById[mission.refId] ?: return@filter false
            remaining(mainMission, mainCounts) == 0 && remaining(mission, appCounts) > 0
        }
        for (mission in blocked.take(5)) {
            val appRemaining = remaining(mission, appCounts)
            add("同编号任务「${mission.name}」：支付宝可排 0 次，App 可排 $appRemaining 次，本轮保留支付宝条目")
        }
        if (blocked.size > 5) add("另有 ${blocked.size - 5} 项 App 任务受到相同的编号冲突影响")
    }

    fun buildWork(missions: List<PlannedMission>, doneCount: Map<String, Int>, maxItems: Int = 30): List<MissionWorkItem> {
        val out = mutableListOf<MissionWorkItem>()
        for (mission in missions) {
            val remaining = remaining(mission, doneCount)
            if (remaining <= 0) continue
            for (round in 1..remaining) {
                out += MissionWorkItem(
                    refId = mission.refId,
                    name = mission.name,
                    score = mission.score,
                    round = round,
                    total = remaining,
                    platform = mission.platform
                )
                if (out.size >= maxItems) return out
            }
        }
        return out
    }

    private fun remaining(mission: PlannedMission, doneCount: Map<String, Int>): Int {
        if (shouldSkip(mission.name) || mission.refId.isBlank() || mission.score <= 0 || mission.limit <= 0) return 0
        return (mission.limit - (doneCount[mission.refId] ?: 0)).coerceAtLeast(0)
    }

    private fun shouldSkip(name: String): Boolean = listOf("免费权益", "借贷", "贷款").any { name.contains(it) }
}
