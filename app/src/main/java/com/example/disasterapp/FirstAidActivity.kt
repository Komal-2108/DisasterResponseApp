package com.example.disasterapp

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.disasterapp.models.FirstAidGuide

class FirstAidActivity : AppCompatActivity() {

    private lateinit var rvFirstAid: RecyclerView
    private lateinit var etSearchGuide: EditText
    private lateinit var adapter: GuideAdapter
    private var guideList = listOf<FirstAidGuide>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_first_aid)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        initializeGuides()

        rvFirstAid = findViewById(R.id.rvFirstAid)
        rvFirstAid.layoutManager = LinearLayoutManager(this)
        adapter = GuideAdapter(guideList) { selectedGuide ->
            showGuideDetails(selectedGuide)
        }
        rvFirstAid.adapter = adapter

        etSearchGuide = findViewById(R.id.etSearchGuide)
        etSearchGuide.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterGuides(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun initializeGuides() {
        guideList = listOf(
            FirstAidGuide(
                title = "CPR Instructions",
                iconResId = android.R.drawable.ic_menu_info_details, // Using generic built-in icons
                keywords = listOf("cpr", "breathing", "heart", "unconscious"),
                instructions = "1. Call for help immediately.\n\n2. Place hands in the center of the chest.\n\n3. Push hard and fast (100-120 compressions per minute).\n\n4. Give 2 rescue breaths after every 30 compressions if trained."
            ),
            FirstAidGuide(
                title = "Wound Bandaging & Bleeding",
                iconResId = android.R.drawable.ic_menu_agenda,
                keywords = listOf("bleeding", "wound", "blood", "cut", "bandage"),
                instructions = "1. Apply direct pressure to the wound using a clean cloth.\n\n2. Elevate the injured area above the heart if possible.\n\n3. Wrap bandage firmly, but do not cut off circulation.\n\n4. Do not remove original cloth if soaked, add layers."
            ),
            FirstAidGuide(
                title = "Fracture Immobilisation",
                iconResId = android.R.drawable.ic_delete,
                keywords = listOf("fracture", "broken", "bone", "arm", "leg", "splint"),
                instructions = "1. Do not try to realign the bone.\n\n2. Immobilize the area using a rigid object (wood, rolled magazine) as a splint.\n\n3. Secure the splint above and below the joint, loosely.\n\n4. Wait for rescue personnel."
            ),
            FirstAidGuide(
                title = "Earthquake & Flood Evacuation",
                iconResId = android.R.drawable.ic_dialog_alert,
                keywords = listOf("earthquake", "flood", "evacuate", "escape", "water", "tsunami"),
                instructions = "EARTHQUAKE:\nDrop, Cover, and Hold On. Avoid doorways. Once shaking stops, move out quickly away from buildings.\n\nFLOOD/TSUNAMI:\nMove immediately to higher ground. Do NOT walk through flowing water. Disconnect electrical appliances."
            )
        )
    }

    private fun filterGuides(query: String) {
        val filteredList = if (query.isBlank()) {
            guideList
        } else {
            val lowerQuery = query.lowercase()
            guideList.filter { guide ->
                guide.title.lowercase().contains(lowerQuery) ||
                guide.keywords.any { keyword -> keyword.contains(lowerQuery) }
            }
        }
        adapter.updateData(filteredList)
    }

    private fun showGuideDetails(guide: FirstAidGuide) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.item_first_aid, null)
        // Re-using item_first_aid partially or just build programmatically for large text
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle(guide.title)
        
        val tvInstructions = TextView(this).apply {
            text = guide.instructions
            textSize = 22f // Large text for stress
            setPadding(48, 48, 48, 48)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@FirstAidActivity, R.color.primaryTextColor))
        }
        
        builder.setView(tvInstructions)
        builder.setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
        
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(R.color.cardBackground)
        dialog.show()
    }

    // Inner Adapter Class
    class GuideAdapter(
        private var list: List<FirstAidGuide>,
        private val onItemClick: (FirstAidGuide) -> Unit
    ) : RecyclerView.Adapter<GuideAdapter.GuideViewHolder>() {

        class GuideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvGuideTitle)
            val ivIcon: ImageView = view.findViewById(R.id.ivGuideIcon)
            val rootLayout: View = view
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_first_aid, parent, false)
            return GuideViewHolder(view)
        }

        override fun onBindViewHolder(holder: GuideViewHolder, position: Int) {
            val guide = list[position]
            holder.tvTitle.text = guide.title
            holder.ivIcon.setImageResource(guide.iconResId)
            holder.rootLayout.setOnClickListener { onItemClick(guide) }
        }

        override fun getItemCount() = list.size

        fun updateData(newList: List<FirstAidGuide>) {
            list = newList
            notifyDataSetChanged()
        }
    }
}
