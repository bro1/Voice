package voice.app.features

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.lifecycle.lifecycleScope
import com.bluelinelabs.conductor.ChangeHandlerFrameLayout
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import voice.app.AppController
import voice.app.features.bookOverview.EditCoverDialogController
import voice.app.features.bookmarks.BookmarkController
import voice.app.injection.appComponent
import voice.app.misc.conductor.asVerticalChangeHandlerTransaction
import voice.common.BookId
import voice.common.navigation.Destination
import voice.common.navigation.NavigationCommand
import voice.common.navigation.Navigator
import voice.common.pref.CurrentBook
import voice.data.Book
import voice.data.repo.BookRepository
import voice.logging.core.Logger
import voice.playback.PlayerController
import voice.playback.session.search.BookSearchHandler
import voice.playback.session.search.BookSearchParser
import voice.playbackScreen.BookPlayController
import javax.inject.Inject
import kotlin.time.Duration

class MainActivity : AppCompatActivity() {

  @field:[
  Inject
  CurrentBook
  ]
  lateinit var currentBook: DataStore<BookId?>

  @Inject
  lateinit var bookRepository: BookRepository

  @Inject
  lateinit var bookSearchParser: BookSearchParser

  @Inject
  lateinit var bookSearchHandler: BookSearchHandler

  @Inject
  lateinit var playerController: PlayerController

  @Inject
  lateinit var navigator: Navigator

  private lateinit var router: Router

  override fun onCreate(savedInstanceState: Bundle?) {
    appComponent.inject(this)
    super.onCreate(savedInstanceState)
    val root = ChangeHandlerFrameLayout(this)
    setContentView(root)

//    window.decorView.setOnKeyListener { _, keyCode, event ->
//      Log.v("LINAS presssed 1", "LP1")
//      if (keyCode == KeyEvent.KEYCODE_1 && event.action == KeyEvent.ACTION_UP) {
//        // Handle the back key press here
//        // You can add your custom logic or simply finish the activity
//
//        finish()
//        return@setOnKeyListener true
//      }
//      // Return false to allow normal processing of the key event
//      false
//    }




    router = Conductor.attachRouter(this, root, savedInstanceState)
      .setOnBackPressedDispatcherEnabled(true)
      .setPopRootControllerMode(Router.PopRootControllerMode.NEVER)
    if (!router.hasRootController()) {
      setupRouter()
    }
    lifecycleScope.launch {
      navigator.navigationCommands.collect { command ->
        when (command) {
          NavigationCommand.GoBack -> {
            if (router.backstack.lastOrNull()?.controller is AppController) {
              // AppController handles it's own navigation commands
            } else {
              router.popCurrentController()
            }
          }
          is NavigationCommand.GoTo -> {
            when (val destination = command.destination) {
              is Destination.Compose -> {
                // no-op
              }
              is Destination.Bookmarks -> {
                router.pushController(BookmarkController(destination.bookId).asVerticalChangeHandlerTransaction())
              }
              is Destination.Playback -> {
                lifecycleScope.launch {
                  currentBook.updateData { destination.bookId }
                  router.pushController(BookPlayController(destination.bookId).asVerticalChangeHandlerTransaction())
                }
              }
              is Destination.Website -> {
                try {
                  startActivity(Intent(Intent.ACTION_VIEW, destination.url.toUri()))
                } catch (exception: ActivityNotFoundException) {
                  Logger.w(exception)
                }
              }
              is Destination.EditCover -> {
                val args = EditCoverDialogController.Arguments(destination.cover, destination.bookId)
                EditCoverDialogController(args).showDialog(router)
              }
              is Destination.Activity -> {
                startActivity(destination.intent)
              }
            }
          }
          is NavigationCommand.Execute -> {
            // handled in AppController
          }
        }
      }
    }

    setupFromIntent(intent)
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setupFromIntent(intent)
  }

  private fun setupFromIntent(intent: Intent?) {
    bookSearchParser.parse(intent)?.let {
      runBlocking {
        bookSearchHandler.handle(it)
      }
    }
  }


  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {

    return when (keyCode) {
      KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> {
        Log.w("VOICE-KEYBOARD", "pressed 6 - next book")

        lifecycleScope.launch {
          var id = bookId(true)
          Log.w("VOICE-KEYBOARD", id.value)
          currentBook.updateData { id }
          router.pushController(BookPlayController(id).asVerticalChangeHandlerTransaction())
        }
        true
      }

      KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> {
        Log.w("VOICE-KEYBOARD", "pressed 4 - previous book")

        lifecycleScope.launch {
          //var r = bookRepository.all()
          var id = bookId(false)
          Log.w("VOICE-KEYBOARD", id.value)
          currentBook.updateData { id }
          router.pushController(BookPlayController(id).asVerticalChangeHandlerTransaction())
        }
        true
      }
      KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
        Log.w("VOICE-KEYBOARD", "pressed 0 - play")

        lifecycleScope.launch {
          playerController.playPause()
        }
        true
      }

      KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> {
        Log.w("VOICE-KEYBOARD", "pressed 2 - pause with rewind")

        lifecycleScope.launch {
          playerController.pauseWithRewind(Duration.parse("2s"))
        }
        true
      }

      KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> {
        Log.w("VOICE-KEYBOARD", "pressed 1 - previous chapter")

        lifecycleScope.launch {
          playerController.previous();
        }
        true
      }


      KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> {
        Log.w("VOICE-KEYBOARD", "pressed 3 - next")

        lifecycleScope.launch {
          playerController.next();
        }
        true
      }


      else -> super.onKeyUp(keyCode, event)
    }
  }

  private suspend fun bookId(next: Boolean): BookId {
    var r = bookRepository.all()
    // using for loop
    for (fruit in r) {
      Log.w("VOICE-KEYBOARD", fruit.toString())
    }

    var pos = 0;
    for (i in 0 until r.size) {
      var z = r.get(i).id
      if (z == currentBook.data.first()) {
        pos = i;
        break
      }
    }

    // If moving to the next book
    if (next) {
      if (pos == r.size - 1) {
        pos = 0
      } else {
        pos = pos + 1
      }
    } else {

      // If moving to the previous book
      if (pos == 0) {
        pos = r.size - 1
      } else {
        pos = pos - 1
      }

    }

    var id = r.get(pos).id;
    return id
  }

  private fun setupRouter() {
    // if we should enter a book set the backstack and return early
    val goToBook = intent.getBooleanExtra(NI_GO_TO_BOOK, false)
    if (goToBook) {
      val bookId = runBlocking { currentBook.data.first() }
      if (bookId != null) {
        val bookShelf = RouterTransaction.with(AppController())
        val bookPlay = BookPlayController(bookId).asVerticalChangeHandlerTransaction()
        router.setBackstack(listOf(bookShelf, bookPlay), null)
        return
      }
    }

    // if we should play the current book, set the backstack and return early
    if (intent?.action == "playCurrent") {
      runBlocking { currentBook.data.first() }?.let { bookId ->
        val bookShelf = RouterTransaction.with(AppController())
        val bookPlay = BookPlayController(bookId).asVerticalChangeHandlerTransaction()
        router.setBackstack(listOf(bookShelf, bookPlay), null)
        playerController.play()
        return
      }
    }

    val rootTransaction = RouterTransaction.with(AppController())
    router.setRoot(rootTransaction)
  }

  companion object {

    private const val NI_GO_TO_BOOK = "niGotoBook"

    fun goToBookIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
      putExtra(NI_GO_TO_BOOK, true)
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    }
  }
}
