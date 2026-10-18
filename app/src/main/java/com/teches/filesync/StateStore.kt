package com.teches.filesync

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

class StateStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("sets.json"))

    fun load(): AppState {
        return synchronized(processLock) {
            val root = try {
                file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            } catch (_: FileNotFoundException) {
                return@synchronized AppState()
            }

            val profilesJson = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = buildList {
                for (index in 0 until profilesJson.length()) {
                    add(profilesJson.getJSONObject(index).toProfile())
                }
            }
            val similarity = root.optJSONObject("similarity")
            AppState(
                profiles,
                root.optString("activeProfileId").takeIf { it.isNotBlank() && it != "null" },
                SimilaritySettings(
                    checkNames = similarity?.optBoolean("check names", true) ?: true,
                    checkContent = similarity?.optBoolean("check content", true) ?: true,
                ),
            )
        }
    }

    @Throws(IOException::class)
    fun save(state: AppState) {
        synchronized(processLock) {
            val root = JSONObject()
                .put("profiles", JSONArray().apply { state.profiles.forEach { put(it.toJson()) } })
                .put("activeProfileId", state.activeProfileId ?: "")
                .put(
                    "similarity",
                    JSONObject()
                        .put("check names", state.similarity.checkNames)
                        .put("check content", state.similarity.checkContent),
                )

            val stream = file.startWrite()
            try {
                stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: IOException) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    private fun JSONObject.toProfile() = Profile(
        id = optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
        uname = getString("uname"),
        email = getString("email"),
        profilePic = optString("profile pic").takeIf(String::isNotBlank),
        colors = getJSONArray("colors").toStringList(),
        drives = getJSONArray("drives").let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val drive = array.getJSONObject(index)
                    add(DriveAccount(drive.getString("email"), drive.optBoolean("signed in")))
                }
            }
        },
        sets = getJSONArray("sets").let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val set = array.getJSONObject(index)
                    val files = set.getJSONArray("files")
                    add(
                        SyncSet(
                            id = set.optString("id").ifBlank {
                                java.util.UUID.randomUUID().toString()
                            },
                            name = set.getString("name"),
                            files = buildList {
                                for (fileIndex in 0 until files.length()) {
                                    val item = files.getJSONObject(fileIndex)
                                    add(
                                        SyncFile(
                                            item.getString("file name"),
                                            item.getString("file type"),
                                            item.getString("location"),
                                            item.optString("baseline hash"),
                                        ),
                                    )
                                }
                            },
                            masterFileLocation = set.optString("master file location")
                                .takeIf { it.isNotBlank() && it != "null" },
                            similarityApproved = set.optBoolean("similarity approved", false),
                        ),
                    )
                }
            }
        },
    )

    private fun Profile.toJson() = JSONObject()
        .put("id", id)
        .put("uname", uname)
        .put("email", email)
        .put("profile pic", profilePic)
        .put("colors", JSONArray(colors))
        .put(
            "drives",
            JSONArray().apply {
                drives.forEach { drive ->
                    put(JSONObject().put("email", drive.email).put("signed in", drive.signedIn))
                }
            },
        )
        .put(
            "sets",
            JSONArray().apply {
                sets.forEach { set ->
                    put(
                        JSONObject()
                            .put("id", set.id)
                            .put("name", set.name)
                            .put("master file location", set.masterFileLocation)
                            .put("similarity approved", set.similarityApproved)
                            .put(
                                "files",
                                JSONArray().apply {
                                    set.files.forEach { item ->
                                        put(
                                            JSONObject()
                                                .put("file name", item.name)
                                                .put("file type", item.type)
                                                .put("location", item.location)
                                                .put("baseline hash", item.baselineHash),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )

    private fun JSONArray.toStringList() = buildList {
        for (index in 0 until length()) add(getString(index))
    }

    companion object {
        internal val processLock = Any()
    }
}
