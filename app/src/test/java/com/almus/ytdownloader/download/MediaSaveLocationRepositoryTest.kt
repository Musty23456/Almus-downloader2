package com.almus.ytdownloader.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The queue hands audio and video to the system with the folder chosen in Settings. */
class MediaSaveLocationRepositoryTest {
    private lateinit var dao: FakeDao
    private lateinit var system: FakeSystem
    private lateinit var storage: FakeStorage
    private lateinit var settings: FakeSettings
    private lateinit var saveSettings: FixedSaveSettings
    private lateinit var repo: DownloadRepository
    private var now = 1_000L

    @Before fun setUp() {
        dao = FakeDao()
        system = FakeSystem()
        storage = FakeStorage()
        settings = FakeSettings(3)
        saveSettings = FixedSaveSettings()
        repo = DownloadRepository(dao, system, storage, FakeNotifier(), settings, saveSettings) { now++ }
    }

    private fun video(name: String = "clip.mp4") = linkInfo(name, 1000, "video/mp4")

    private fun audio(name: String = "song.mp3") = linkInfo(name, 1000, "audio/mpeg")

    private suspend fun startId(info: LinkInfo) = (repo.start(info) as StartResult.Started).id

    @Test fun singleLayoutSendsNoSubfolder() = runBlocking<Unit> {
        startId(video())
        startId(audio())
        startId(linkInfo("a.zip"))
        assertEquals(3, system.enqueued.size)
        system.enqueued.forEach { assertNull(it.subfolder) }
    }

    @Test fun byMediaLayoutSortsVideoAudioAndTheRest() = runBlocking<Unit> {
        saveSettings.layout = SaveLayout.ByMedia
        startId(video())
        startId(audio())
        startId(linkInfo("a.zip"))
        assertEquals(listOf("Video", "Audio", null), system.enqueued.map { it.subfolder })
        assertEquals(listOf("clip.mp4", "song.mp3", "a.zip"), system.enqueued.map { it.fileName })
    }

    @Test fun theOriginalFileTypeAndNameAreHandedOverUntouched() = runBlocking<Unit> {
        saveSettings.layout = SaveLayout.ByMedia
        startId(linkInfo("Movie Night.webm", 5000, "video/webm", url = "https://example.com/dl?id=7"))
        val request = system.enqueued.single()
        assertEquals("Movie Night.webm", request.fileName)
        assertEquals("video/webm", request.mimeType)
        assertEquals("https://example.com/dl?id=7", request.url)
    }

    @Test fun nameCollisionsAreCheckedInTheFolderTheFileWillGoTo() = runBlocking<Unit> {
        storage.existing = setOf("Video/clip.mp4")

        saveSettings.layout = SaveLayout.ByMedia
        val inVideoFolder = startId(video())
        assertEquals("clip (1).mp4", dao.getById(inVideoFolder)!!.fileName)
    }

    @Test fun aFileInAnotherFolderIsNotACollision() = runBlocking<Unit> {
        storage.existing = setOf("Video/clip.mp4")

        // Everything goes to the main folder with this layout, where no clip.mp4 exists.
        saveSettings.layout = SaveLayout.Single
        val id = startId(video())
        assertEquals("clip.mp4", dao.getById(id)!!.fileName)
    }

    @Test fun theLayoutInForceWhenTheDownloadIsHandedOverIsUsed() = runBlocking<Unit> {
        settings.maxConcurrent = 1
        val first = startId(linkInfo("a.zip"))
        startId(video()) // waits in the app queue
        assertEquals(1, system.enqueued.size)

        saveSettings.layout = SaveLayout.ByMedia
        system.snapshots[dao.getById(first)!!.systemId!!] =
            SystemSnapshot(StatusMapper.STATUS_SUCCESSFUL, 0, 1000, 1000, null, null)
        repo.refresh()

        assertEquals(2, system.enqueued.size)
        assertEquals("Video", system.enqueued[1].subfolder)
    }

    @Test fun retryUsesTheCurrentLayout() = runBlocking<Unit> {
        val id = startId(video())
        val systemId = dao.getById(id)!!.systemId!!
        system.snapshots[systemId] = SystemSnapshot(StatusMapper.STATUS_FAILED, 503, 0, -1, null, null)
        repo.refresh()

        saveSettings.layout = SaveLayout.ByMedia
        assertEquals(StartResult.Started(id, queued = false), repo.retry(id))
        assertEquals("Video", system.enqueued.last().subfolder)
    }
}
