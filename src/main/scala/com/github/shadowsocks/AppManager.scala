/*******************************************************************************/
/*                                                                             */
/*  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          */
/*  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  */
/*                                                                             */
/*  This program is free software: you can redistribute it and/or modify       */
/*  it under the terms of the GNU General Public License as published by       */
/*  the Free Software Foundation, either version 3 of the License, or          */
/*  (at your option) any later version.                                        */
/*                                                                             */
/*  This program is distributed in the hope that it will be useful,            */
/*  but WITHOUT ANY WARRANTY; without even the implied warranty of             */
/*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              */
/*  GNU General Public License for more details.                               */
/*                                                                             */
/*  You should have received a copy of the GNU General Public License          */
/*  along with this program. If not, see <http://www.gnu.org/licenses/>.       */
/*                                                                             */
/*******************************************************************************/

package com.github.shadowsocks

import java.util.concurrent.atomic.AtomicBoolean

import android.Manifest.permission
import android.animation.{Animator, AnimatorListenerAdapter}
import android.app.TaskStackBuilder
import android.content._
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.{Bundle, Handler}
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import android.support.v7.widget.{DefaultItemAnimator, LinearLayoutManager, RecyclerView, Toolbar}
import android.view._
import android.widget._
import com.futuremind.recyclerviewfastscroll.{FastScroller, SectionTitleProvider}
import com.github.shadowsocks.ShadowsocksApplication.app
import com.github.shadowsocks.utils.{Key, Utils}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.language.implicitConversions

object AppManager {
  case class ProxiedApp(name: String, packageName: String, icon: Drawable)
  private case class ListEntry(switch: Switch, text: TextView, icon: ImageView)

  private var instance: AppManager = _

  private var receiverRegistered: Boolean = _
  private var cachedApps: Array[ProxiedApp] = _
  private def getApps(pm: PackageManager) = {
    if (!receiverRegistered) {
      val filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED)
      filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
      filter.addDataScheme("package")
      app.registerReceiver((_: Context, intent: Intent) =>
        if (intent.getAction != Intent.ACTION_PACKAGE_REMOVED ||
          !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
          synchronized(cachedApps = null)
          val instance = AppManager.instance
          if (instance != null) instance.reloadApps()
        }, filter)
      receiverRegistered = true
    }
    synchronized {
      if (cachedApps == null) cachedApps = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        .filter(p => p.requestedPermissions != null && p.requestedPermissions.contains(permission.INTERNET))
        .map(p => ProxiedApp(pm.getApplicationLabel(p.applicationInfo).toString, p.packageName,
          p.applicationInfo.loadIcon(pm))).toArray
      cachedApps
    }
  }
}

class AppManager extends AppCompatActivity with OnMenuItemClickListener {
  import AppManager._

  private final class AppViewHolder(val view: View) extends RecyclerView.ViewHolder(view) with View.OnClickListener {
    private val icon = itemView.findViewById(R.id.itemicon).asInstanceOf[ImageView]
    private val check = itemView.findViewById(R.id.itemcheck).asInstanceOf[Switch]
    private var item: ProxiedApp = _
    itemView.setOnClickListener(this)

    private def proxied = proxiedApps.contains(item.packageName)

    def bind(app: ProxiedApp) {
      this.item = app
      icon.setImageDrawable(app.icon)
      check.setText(app.name)
      check.setChecked(proxied)
    }

    def onClick(v: View) {
      if (proxied) {
        proxiedApps.remove(item.packageName)
        check.setChecked(false)
      } else {
        proxiedApps.add(item.packageName)
        check.setChecked(true)
      }
      if (!appsLoading.get) app.editor.putString(Key.individual, proxiedApps.mkString("\n")).apply()
    }
  }

  private final class AppsAdapter extends RecyclerView.Adapter[AppViewHolder] with SectionTitleProvider {
    private val apps = getApps(getPackageManager).sortWith((a, b) => {
      val aProxied = proxiedApps.contains(a.packageName)
      if (aProxied ^ proxiedApps.contains(b.packageName)) aProxied else a.name.compareToIgnoreCase(b.name) < 0
    })

    def getItemCount: Int = apps.length
    def onBindViewHolder(vh: AppViewHolder, i: Int): Unit = vh.bind(apps(i))
    def onCreateViewHolder(vg: ViewGroup, i: Int) =
      new AppViewHolder(LayoutInflater.from(vg.getContext).inflate(R.layout.layout_apps_item, vg, false))
    def getSectionTitle(i: Int): String = apps(i).name.substring(0, 1)
  }

  private var proxiedApps: mutable.HashSet[String] = _
  private var toolbar: Toolbar = _
  private var bypassSwitch: Switch = _
  private var appListView: RecyclerView = _
  private var fastScroller: FastScroller = _
  private var loadingView: View = _
  private val appsLoading = new AtomicBoolean
  private var handler: Handler = _

  private def initProxiedApps(str: String = app.settings.getString(Key.individual, null)) =
    proxiedApps = str.split('\n').to[mutable.HashSet]

  override def onDestroy() {
    instance = null
    super.onDestroy()
    if (handler != null) {
      handler.removeCallbacksAndMessages(null)
      handler = null
    }
  }

  def onMenuItemClick(item: MenuItem): Boolean = {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
    item.getItemId match {
      case R.id.action_apply_all =>
        app.profileManager.getAllProfiles match {
          case Some(profiles) =>
            val proxiedAppString = app.settings.getString(Key.individual, null)
            profiles.foreach(p => {
              p.individual = proxiedAppString
              app.profileManager.updateProfile(p)
            })
            Toast.makeText(this, R.string.action_apply_all, Toast.LENGTH_SHORT).show()
          case _ => Toast.makeText(this, R.string.action_export_err, Toast.LENGTH_SHORT).show()
        }
        return true
      case R.id.action_export =>
        val bypass = app.settings.getBoolean(Key.bypass, false)
        val proxiedAppString = app.settings.getString(Key.individual, null)
        val clip = ClipData.newPlainText(Key.individual, bypass + "\n" + proxiedAppString)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.action_export_msg, Toast.LENGTH_SHORT).show()
        return true
      case R.id.action_import =>
        if (clipboard.hasPrimaryClip) {
          val proxiedAppSequence = clipboard.getPrimaryClip.getItemAt(0).getText
          if (proxiedAppSequence != null) {
            val proxiedAppString = proxiedAppSequence.toString
            if (!proxiedAppString.isEmpty) {
              val i = proxiedAppString.indexOf('\n')
              try {
                val (enabled, apps) = if (i < 0) (proxiedAppString, "")
                  else (proxiedAppString.substring(0, i), proxiedAppString.substring(i + 1))
                bypassSwitch.setChecked(enabled.toBoolean)
                app.editor.putString(Key.individual, apps).putBoolean(Key.dirty, true).apply()
                Toast.makeText(this, R.string.action_import_msg, Toast.LENGTH_SHORT).show()
                appListView.setVisibility(View.GONE)
                fastScroller.setVisibility(View.GONE)
                loadingView.setVisibility(View.VISIBLE)
                initProxiedApps(apps)
                reloadApps()
                return true
              } catch {
                case _: IllegalArgumentException =>
                  Toast.makeText(this, R.string.action_import_err, Toast.LENGTH_SHORT).show()
              }
            }
          }
        }
        Toast.makeText(this, R.string.action_import_err, Toast.LENGTH_SHORT).show()
        return false
    }
    false
  }

  protected override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)

    handler = new Handler()

    this.setContentView(R.layout.layout_apps)
    toolbar = findViewById(R.id.toolbar).asInstanceOf[Toolbar]
    toolbar.setTitle(R.string.proxied_apps)
    toolbar.setNavigationIcon(R.drawable.abc_ic_ab_back_material)
    toolbar.setNavigationOnClickListener(_ => {
      val intent = getParentActivityIntent
      if (shouldUpRecreateTask(intent) || isTaskRoot)
        TaskStackBuilder.create(this).addNextIntentWithParentStack(intent).startActivities()
      else finish()
    })
    toolbar.inflateMenu(R.menu.app_manager_menu)
    toolbar.setOnMenuItemClickListener(this)

    if (!app.settings.getBoolean(Key.proxyApps, false))
      app.editor.putBoolean(Key.proxyApps, true).putBoolean(Key.dirty, true).apply()
    findViewById(R.id.onSwitch).asInstanceOf[Switch]
      .setOnCheckedChangeListener((_, checked) => {
        app.editor.putBoolean(Key.proxyApps, checked).putBoolean(Key.dirty, true).apply()
        finish()
      })

    bypassSwitch = findViewById(R.id.bypassSwitch).asInstanceOf[Switch]
    bypassSwitch.setChecked(app.settings.getBoolean(Key.bypass, false))
    bypassSwitch.setOnCheckedChangeListener((_, checked) =>
      app.editor.putBoolean(Key.bypass, checked).putBoolean(Key.dirty, true).apply())

    initProxiedApps()
    loadingView = findViewById(R.id.loading)
    appListView = findViewById(R.id.list).asInstanceOf[RecyclerView]
    appListView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false))
    appListView.setItemAnimator(new DefaultItemAnimator)
    fastScroller = findViewById(R.id.fastscroller).asInstanceOf[FastScroller]

    instance = this
    loadAppsAsync()
  }

  def reloadApps(): Unit = if (!appsLoading.compareAndSet(true, false)) loadAppsAsync()
  def loadAppsAsync() {
    if (!appsLoading.compareAndSet(false, true)) return
    Utils.ThrowableFuture {
      var adapter: AppsAdapter = null
      do {
        appsLoading.set(true)
        adapter = new AppsAdapter
      } while (!appsLoading.compareAndSet(true, false))
      handler.post(() => {
        appListView.setAdapter(adapter)
        fastScroller.setRecyclerView(appListView)
        val shortAnimTime = getResources.getInteger(android.R.integer.config_shortAnimTime)
        appListView.setAlpha(0)
        appListView.setVisibility(View.VISIBLE)
        appListView.animate().alpha(1).setDuration(shortAnimTime)
        fastScroller.setAlpha(0)
        fastScroller.setVisibility(View.VISIBLE)
        fastScroller.animate().alpha(1).setDuration(shortAnimTime)
        loadingView.animate().alpha(0).setDuration(shortAnimTime).setListener(new AnimatorListenerAdapter {
          override def onAnimationEnd(animation: Animator): Unit = loadingView.setVisibility(View.GONE)
        })
      })
    }
  }

  override def onKeyUp(keyCode: Int, event: KeyEvent): Boolean = keyCode match {
    case KeyEvent.KEYCODE_MENU =>
      if (toolbar.isOverflowMenuShowing) toolbar.hideOverflowMenu else toolbar.showOverflowMenu
    case _ => super.onKeyUp(keyCode, event)
  }
}
