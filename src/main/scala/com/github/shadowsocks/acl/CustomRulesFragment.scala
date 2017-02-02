package com.github.shadowsocks.acl

import java.net.IDN
import java.util.Locale

import android.content.{ClipData, ClipboardManager, Context, DialogInterface}
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AlertDialog
import android.support.v7.widget.RecyclerView.ViewHolder
import android.support.v7.widget.helper.ItemTouchHelper
import android.support.v7.widget.helper.ItemTouchHelper.SimpleCallback
import android.support.v7.widget.{DefaultItemAnimator, LinearLayoutManager, RecyclerView, Toolbar}
import android.view._
import android.widget.{EditText, Spinner, TextView, Toast}
import com.futuremind.recyclerviewfastscroll.{FastScroller, SectionTitleProvider}
import com.github.shadowsocks.ShadowsocksApplication.app
import com.github.shadowsocks.utils.State
import com.github.shadowsocks.widget.UndoSnackbarManager
import com.github.shadowsocks.{MainActivity, R, ToolbarFragment}

import scala.collection.mutable

/**
  * @author Mygod
  */
object CustomRulesFragment {
  private final val GENERIC = 0
  private final val DOMAIN = 1
  private val PATTERN_DOMAIN = """(?<=^\(\^\|\\\.\)).*(?=\$$)""".r
  private final val TEMPLATE_DOMAIN = "(^|\\.)%s$"

  private final val SELECTED_SUBNETS = "com.github.shadowsocks.acl.CustomRulesFragment.SELECTED_SUBNETS"
  private final val SELECTED_HOSTNAMES = "com.github.shadowsocks.acl.CustomRulesFragment.SELECTED_HOSTNAMES"
}

class CustomRulesFragment extends ToolbarFragment with Toolbar.OnMenuItemClickListener {
  import CustomRulesFragment._

  private def isEnabled = getActivity.asInstanceOf[MainActivity].state match {
    case State.CONNECTED => app.currentProfile.get.route != Acl.CUSTOM_RULES
    case State.STOPPED => true
    case _ => false
  }

  private def createAclRuleDialog(text: CharSequence = "") = {
    val view = getActivity.getLayoutInflater.inflate(R.layout.dialog_acl_rule, null)
    val templateSelector = view.findViewById(R.id.template_selector).asInstanceOf[Spinner]
    val editText = view.findViewById(R.id.content).asInstanceOf[EditText]
    PATTERN_DOMAIN.findFirstMatchIn(text) match {
      case Some(m) =>
        templateSelector.setSelection(DOMAIN)
        editText.setText(IDN.toUnicode(m.matched.replaceAll("\\\\.", "."),
          IDN.ALLOW_UNASSIGNED | IDN.USE_STD3_ASCII_RULES))
      case None =>
        templateSelector.setSelection(GENERIC)
        editText.setText(text)
    }
    (templateSelector, editText, new AlertDialog.Builder(getActivity)
      .setTitle(R.string.edit_rule)
      .setNegativeButton(android.R.string.cancel, null)
      .setView(view))
  }

  private val selectedItems = new mutable.HashSet[AnyRef]
  private def onSelectedItemsUpdated(): Unit = if (selectionItem != null) {
    selectionItem.setVisible(selectedItems.nonEmpty)
  }

  private final class AclRuleViewHolder(view: View) extends RecyclerView.ViewHolder(view)
    with View.OnClickListener with View.OnLongClickListener {
    var item: AnyRef = _
    private val text = itemView.findViewById(android.R.id.text1).asInstanceOf[TextView]
    itemView.setOnClickListener(this)
    itemView.setOnLongClickListener(this)
    itemView.setBackgroundResource(R.drawable.background_selectable)

    def bind(hostname: String) {
      item = hostname
      text.setText(hostname)
      itemView.setSelected(selectedItems.contains(hostname))
    }
    def bind(subnet: Subnet) {
      item = subnet
      text.setText(subnet.toString)
      itemView.setSelected(selectedItems.contains(subnet))
    }

    def onClick(v: View): Unit = if (selectedItems.nonEmpty) onLongClick(v) else {
      val (templateSelector, editText, dialog) = createAclRuleDialog(item.toString)
      dialog
        .setNeutralButton(R.string.delete, ((_, _) => {
          adapter.remove(item)
          undoManager.remove((-1, item))
        }): DialogInterface.OnClickListener)
        .setPositiveButton(android.R.string.ok, ((_, _) =>
          adapter.addFromTemplate(templateSelector.getSelectedItemPosition, editText.getText) match {
            case -1 =>
            case index =>
              val item = this.item
              list.post(() => {
                list.scrollToPosition(index)
                adapter.remove(item)
              })
          })
          : DialogInterface.OnClickListener)
        .create().show()
    }
    def onLongClick(v: View): Boolean = {
      if (!selectedItems.add(item)) selectedItems.remove(item)  // toggle
      onSelectedItemsUpdated()
      itemView.setSelected(!itemView.isSelected)
      true
    }
  }

  private final class AclRulesAdapter extends RecyclerView.Adapter[AclRuleViewHolder] with SectionTitleProvider {
    private val acl = Acl.customRules
    private var savePending: Boolean = _
    private def apply() = if (!savePending) {
      savePending = true
      list.post(() => {
        Acl.save(Acl.CUSTOM_RULES, acl)
        savePending = false
      })
    }

    def getItemCount: Int = acl.subnets.size + acl.proxyHostnames.size
    def onBindViewHolder(vh: AclRuleViewHolder, i: Int): Unit = {
      val j = i - acl.subnets.size
      if (j < 0) vh.bind(acl.subnets(i)) else vh.bind(acl.proxyHostnames(j))
    }
    def onCreateViewHolder(vg: ViewGroup, i: Int) = new AclRuleViewHolder(LayoutInflater.from(vg.getContext)
      .inflate(android.R.layout.simple_list_item_1, vg, false))

    def addSubnet(subnet: Subnet): Int = if (acl.subnets.add(subnet)) {
      val index = acl.subnets.indexOf(subnet)
      notifyItemInserted(index)
      apply()
      index
    } else -1
    def addHostname(hostname: String): Int = if (acl.proxyHostnames.add(hostname)) {
      val index = acl.proxyHostnames.indexOf(hostname) + acl.subnets.size
      notifyItemInserted(index)
      apply()
      index
    } else -1
    def addToProxy(input: String): Int = try addSubnet(Subnet.fromString(input)) catch {
      case _: IllegalArgumentException => addHostname(input)
    }
    def addFromTemplate(template: Int, text: CharSequence): Int = template match {
      case GENERIC => addToProxy(text.toString)
      case DOMAIN => try addHostname(TEMPLATE_DOMAIN.formatLocal(Locale.ENGLISH,
        IDN.toASCII(text.toString, IDN.ALLOW_UNASSIGNED | IDN.USE_STD3_ASCII_RULES).replaceAll("\\.", "\\\\."))) catch {
        case exc: IllegalArgumentException =>
          Toast.makeText(getActivity, exc.getMessage, Toast.LENGTH_SHORT).show()
          -1
      }
      case _ => -1
    }

    def remove(i: Int) {
      val j = i - acl.subnets.size
      if (j < 0) {
        undoManager.remove((i, acl.subnets(i)))
        acl.subnets.remove(i)
      } else {
        undoManager.remove((j, acl.proxyHostnames(j)))
        acl.proxyHostnames.remove(j)
      }
      notifyItemRemoved(i)
      apply()
    }
    def remove(item: AnyRef): Unit = item match {
      case subnet: Subnet =>
        notifyItemRemoved(acl.subnets.indexOf(subnet))
        acl.subnets.remove(subnet)
        apply()
      case hostname: String =>
        notifyItemRemoved(acl.proxyHostnames.indexOf(hostname))
        acl.proxyHostnames.remove(hostname)
        apply()
    }
    def removeSelected() {
      undoManager.remove(selectedItems.map((-1, _)).toSeq: _*)
      selectedItems.foreach(remove)
      selectedItems.clear()
      onSelectedItemsUpdated()
    }
    def undo(actions: Iterator[(Int, AnyRef)]): Unit = for ((_, item) <- actions) item match {
      case hostname: String => if (acl.proxyHostnames.insert(hostname)) {
        notifyItemInserted(acl.proxyHostnames.indexOf(hostname) + acl.subnets.size)
        apply()
      }
      case subnet: Subnet => if (acl.subnets.insert(subnet)) {
        notifyItemInserted(acl.subnets.indexOf(subnet))
        apply()
      }
    }

    def selectAll() {
      selectedItems.clear()
      selectedItems ++= acl.subnets
      selectedItems ++= acl.proxyHostnames
      onSelectedItemsUpdated()
      notifyDataSetChanged()
    }

    def getSectionTitle(i: Int): String = {
      val j = i - acl.subnets.size
      try {
        (if (j < 0) acl.subnets(i).address.getHostAddress.substring(0, 1) else {
          val hostname = acl.proxyHostnames(i)
          PATTERN_DOMAIN.findFirstMatchIn(hostname) match {
            case Some(m) => m.matched.replaceAll("\\\\.", ".")  // don't convert IDN yet
            case None => hostname
          }
        }).substring(0, 1)
      } catch {
        case _: IndexOutOfBoundsException => " "
      }
    }
  }

  private lazy val adapter = new AclRulesAdapter()
  private var list: RecyclerView = _
  private var selectionItem: MenuItem = _
  private var undoManager: UndoSnackbarManager[AnyRef] = _
  private lazy val clipboard = getActivity.getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.layout_custom_rules, container, false)
  override def onViewCreated(view: View, savedInstanceState: Bundle) {
    super.onViewCreated(view, savedInstanceState)
    if (savedInstanceState != null) {
      savedInstanceState.getStringArray(SELECTED_SUBNETS) match {
        case null =>
        case arr => selectedItems ++= arr.map(Subnet.fromString)
      }
      savedInstanceState.getStringArray(SELECTED_HOSTNAMES) match {
        case null =>
        case arr => selectedItems ++= arr
      }
      onSelectedItemsUpdated()
    }
    toolbar.setTitle(R.string.custom_rules)
    toolbar.inflateMenu(R.menu.custom_rules_menu)
    toolbar.setOnMenuItemClickListener(this)
    selectionItem = toolbar.getMenu.findItem(R.id.selection)
    selectionItem.setVisible(selectedItems.nonEmpty)
    list = view.findViewById(R.id.list).asInstanceOf[RecyclerView]
    list.setLayoutManager(new LinearLayoutManager(getActivity, LinearLayoutManager.VERTICAL, false))
    list.setItemAnimator(new DefaultItemAnimator)
    list.setAdapter(adapter)
    val fastScroller = view.findViewById(R.id.fastscroller).asInstanceOf[FastScroller]
    fastScroller.setRecyclerView(list)
    undoManager = new UndoSnackbarManager[AnyRef](getActivity.findViewById(R.id.snackbar), adapter.undo)
    new ItemTouchHelper(new SimpleCallback(0, ItemTouchHelper.START | ItemTouchHelper.END) {
      override def getSwipeDirs(recyclerView: RecyclerView, viewHolder: ViewHolder): Int =
        if (isEnabled) super.getSwipeDirs(recyclerView, viewHolder) else 0
      def onSwiped(viewHolder: ViewHolder, direction: Int): Unit = adapter.remove(viewHolder.getAdapterPosition)
      def onMove(recyclerView: RecyclerView, viewHolder: ViewHolder, target: ViewHolder): Boolean = false
    }).attachToRecyclerView(list)
  }

  override def onSaveInstanceState(outState: Bundle): Unit = {
    super.onSaveInstanceState(outState)
    outState.putStringArray(SELECTED_SUBNETS, selectedItems.filter(_.isInstanceOf[Subnet]).map(_.toString).toArray)
    outState.putStringArray(SELECTED_HOSTNAMES, selectedItems.filter(_.isInstanceOf[String]).map(_.toString).toArray)
  }

  override def onMenuItemClick(menuItem: MenuItem): Boolean = menuItem.getItemId match {
    case R.id.action_select_all =>
      adapter.selectAll()
      true
    case R.id.action_cut =>
      clipboard.setPrimaryClip(ClipData.newPlainText(null, selectedItems.mkString("\n")))
      adapter.removeSelected()
      true
    case R.id.action_copy =>
      clipboard.setPrimaryClip(ClipData.newPlainText(null, selectedItems.mkString("\n")))
      true
    case R.id.action_delete =>
      adapter.removeSelected()
      true

    case R.id.action_manual_settings =>
      val (templateSelector, editText, dialog) = createAclRuleDialog()
      dialog
        .setPositiveButton(android.R.string.ok, ((_, _) => adapter.addFromTemplate(
          templateSelector.getSelectedItemPosition, editText.getText)): DialogInterface.OnClickListener)
        .create().show()
      true
    case R.id.action_import =>
      try {
        val items = clipboard.getPrimaryClip.getItemAt(0).getText.toString.split("\n")
        if (items.nonEmpty) {
          items.foreach(adapter.addToProxy)
          return true
        }
      } catch {
        case _: Exception =>
      }
      Snackbar.make(getActivity.findViewById(R.id.snackbar), R.string.action_import_err, Snackbar.LENGTH_LONG).show()
      true
    case R.id.action_import_gfwlist =>
      val acl = new Acl().fromId(Acl.GFWLIST)
      acl.subnets.foreach(adapter.addSubnet)
      acl.proxyHostnames.foreach(adapter.addHostname)
      true
    case _ => false
  }
}
