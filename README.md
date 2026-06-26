# 🚀 QwenRocket (LlamaRocket)

**QwenRocket** (formerly known as LlamaRocket) is an AI-Enhanced fork of the OpenRocket simulator. It features an integrated AI Assistant powered by local LLMs (Qwen/Llama) to help you design, optimize, and simulate multi-stage rockets through natural language prompts.

![Qwen Assistant Interface](qwen-panel.png)

## 🤖 AI Assistant Features
- **Natural Language Design**: Describe your rocket (e.g., "Build a 2-stage rocket with a 250m apogee and 400g payload") and the AI will construct the component tree.
- **Smart Component Management**: Add and manage Transitions, Parachutes, Shock Cords, Nose Cones, Inner Tubes, and more directly through chat.
- **Auto-Optimization**: The AI automatically runs simulations, identifies issues (e.g., missing motors, unstable designs), and adjusts parameters to achieve target goals.
- **Dynamic Motor Injection**: Selects and assigns real motors from your local database based on simulation requirements.
- **Local & Private**: Powered locally via Ollama, ensuring your prompt data and designs never leave your computer.

---

## 📋 Yol Haritası / Yapılacaklar Listesi (To-Do List)

Aşağıda projenin mevcut durumu ve gelecekte eklenecek özelliklerin bir listesi bulunmaktadır:

- [x] **Temel AI Entegrasyonu:** OpenRocket UI içine Qwen/Llama sohbet panelinin (Qwen Assistant Panel) eklenmesi.
- [x] **Parça Ekleme/Çıkarma:** Promptlar aracılığıyla roket ağacına (component tree) müdahale.
- [x] **Motor Atama:** Yapay zekanın roket aşamalarına uygun motorları seçip simülasyonu otomatik çalıştırması.
- [x] **Hata Yakalama (Debug):** Eksik parçaların (örn. burun konisi veya faydalı yük bölümü) tespit edilip kullanıcı müdahalesi olmadan AI tarafından eklenmesi.
- [ ] **Gelişmiş Optimizasyon:** Ağırlık merkezi (CG) ve basınç merkezi (CP) optimizasyonu için AI destekli aerodinamik düzeltmeler.
- [ ] **Çoklu Dil Desteği:** Asistanın Türkçe dışında diğer dillerde de (İngilizce vb.) tamamen kararlı çalışmasının sağlanması.
- [ ] **Özelleştirilmiş LLM Modelleri:** Roket bilimi ve aerodinamik için özel fine-tune edilmiş hafif lokal modellerin (LoRA) entegrasyonu.
- [ ] **Otonom Test ve Raporlama:** Roket uçuş profillerinin otomatik test edilmesi ve raporlanması.

---

## 🛠️ Kurulum & Kullanım (Getting Started)

1. **Ollama Kurulumu:** Bilgisayarınıza [Ollama](https://ollama.com) kurun ve `qwen` veya uygun bir model indirin (`ollama run qwen`).
2. **Projeyi Derleyin:** 
   ```bash
   ./gradlew build
   ```
3. **Çalıştırın:**
   ```bash
   ./gradlew swing:run
   ```
4. **AI Asistanı Kullanın:** Sağ panelde açılan sekmeden roketiniz hakkında komutlar vermeye başlayın. (Örn: "Birinci aşamaya C6-5 motoru ekle ve simülasyonu çalıştır.")

## 📜 Lisans
OpenRocket is proudly open-source under the [GNU GPL](https://www.gnu.org/licenses/gpl-3.0.en.html) license. 
