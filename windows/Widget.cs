using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using Microsoft.Win32;
using WF = System.Windows.Forms;

namespace PhoenixMeteo
{
    public static class Program
    {
        [STAThread]
        public static void Main()
        {
            using var mutex = new System.Threading.Mutex(true, "PhoenixProMeteoWidget", out bool firstInstance);
            if (!firstInstance) return; // déjà lancé
            var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            var w = new WidgetWindow();
            w.Show();
            app.Run();
        }
    }

    /// <summary>Réglages enregistrés dans %AppData%\PhoenixProMeteo\settings.json</summary>
    public class Settings
    {
        public string Name { get; set; } = "Foëcy";
        public string Region { get; set; } = "Centre-Val de Loire";
        public double Lat { get; set; } = 47.1764;
        public double Lon { get; set; } = 2.1622;
        public double? Left { get; set; }
        public double? Top { get; set; }
        public bool Topmost { get; set; } = true;
        public bool Compact { get; set; } = false;

        static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhoenixProMeteo");
        static string FilePath => Path.Combine(Dir, "settings.json");
        public static bool Exists => File.Exists(FilePath);

        public static Settings Load()
        {
            try { if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings(); }
            catch { }
            return new Settings();
        }

        public void Save()
        {
            try { Directory.CreateDirectory(Dir); File.WriteAllText(FilePath, JsonSerializer.Serialize(this)); }
            catch { }
        }
    }

    public class WidgetWindow : Window
    {
        // Couleurs Phoenix Pro
        static readonly Brush BgCard = Hex("#F20E1012");
        static readonly Brush BgCard2 = Hex("#FF181B1E");
        static readonly Brush Line = Hex("#FF2A2F35");
        static readonly Brush TextMain = Hex("#FFF1F3F4");
        static readonly Brush TextMuted = Hex("#FF9AA1A8");
        static readonly Brush Orange = Hex("#FFF08A1C");
        static readonly Brush Blue = Hex("#FF6F9BFF");
        static readonly FontFamily Emoji = new FontFamily("Segoe UI Emoji");
        static readonly FontFamily Ui = new FontFamily("Segoe UI Variable Display, Segoe UI");
        static readonly CultureInfo Fr = new CultureInfo("fr-FR");
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunName = "PhoenixProMeteo";

        readonly Settings s;
        readonly HttpClient http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        readonly DispatcherTimer timer = new DispatcherTimer();
        readonly DispatcherTimer searchDelay = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        WF.NotifyIcon tray;
        WF.ToolStripMenuItem trayTop, trayAuto, trayCompact;

        TextBlock tCity, tRegion, tIcon, tTemp, tDesc, tFeels, tMinMax, tWind, tRain, tHum, tUv, tUpdated, hoursTitle;
        UniformGrid hoursGrid;
        StackPanel daysPanel, detailsPanel, searchResults;
        Border searchOverlay, pinBtn;
        TextBox searchBox;

        public WidgetWindow()
        {
            bool firstRun = !Settings.Exists;
            s = Settings.Load();
            http.DefaultRequestHeaders.UserAgent.ParseAdd("PhoenixProMeteo/1.0");

            Title = "Phoenix Pro Météo";
            WindowStyle = WindowStyle.None;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ResizeMode = ResizeMode.NoResize;
            ShowInTaskbar = false;
            Width = 360;
            SizeToContent = SizeToContent.Height;
            Topmost = s.Topmost;
            FontFamily = Ui;
            WindowStartupLocation = WindowStartupLocation.Manual;
            PlaceWindow();

            Content = BuildUi();
            BuildTray();
            if (firstRun) { SetAutoStart(true); s.Save(); }
            ApplyCompact();

            timer.Interval = TimeSpan.FromMinutes(30);
            timer.Tick += async (o, e) => await Refresh();
            timer.Start();
            searchDelay.Tick += async (o, e) => { searchDelay.Stop(); await Search(searchBox.Text); };
            SystemEvents.PowerModeChanged += async (o, e) => { if (e.Mode == PowerModes.Resume) { await Task.Delay(8000); await Dispatcher.InvokeAsync(async () => await Refresh()); } };

            Loaded += async (o, e) => await Refresh();
        }

        // ---------- Position ----------
        void PlaceWindow()
        {
            var wa = SystemParameters.WorkArea;
            double left = s.Left ?? (wa.Right - Width - 16);
            double top = s.Top ?? (wa.Top + 16);
            // si l'écran a changé, on revient dans la zone visible
            if (left < SystemParameters.VirtualScreenLeft || left > SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 80) left = wa.Right - Width - 16;
            if (top < SystemParameters.VirtualScreenTop || top > SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 80) top = wa.Top + 16;
            Left = left; Top = top;
        }

        // ---------- Interface ----------
        UIElement BuildUi()
        {
            var card = new Border
            {
                CornerRadius = new CornerRadius(18),
                Background = BgCard,
                BorderBrush = Line,
                BorderThickness = new Thickness(1),
                Padding = new Thickness(16, 12, 16, 12),
                Margin = new Thickness(12),
                Effect = new DropShadowEffect { BlurRadius = 20, ShadowDepth = 3, Opacity = 0.45 }
            };
            card.MouseLeftButtonDown += (o, e) =>
            {
                if (e.ClickCount == 2) { s.Compact = !s.Compact; ApplyCompact(); s.Save(); return; }
                try { DragMove(); } catch { }
                s.Left = Left; s.Top = Top; s.Save();
            };

            var root = new Grid();
            var main = new StackPanel();
            root.Children.Add(main);

            // En-tête
            var head = new DockPanel { LastChildFill = true, Margin = new Thickness(0, 0, 0, 4) };
            var btns = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Top };
            DockPanel.SetDock(btns, Dock.Right);
            btns.Children.Add(IconButton("🔍", "Changer de ville", () => OpenSearch()));
            btns.Children.Add(IconButton("⟳", "Actualiser", async () => await Refresh()));
            pinBtn = IconButton("📌", "Toujours au premier plan", () => SetTopmost(!s.Topmost));
            btns.Children.Add(pinBtn);
            btns.Children.Add(IconButton("—", "Masquer (reste près de l'horloge)", () => Hide()));
            head.Children.Add(btns);
            var titles = new StackPanel();
            tCity = new TextBlock { Text = s.Name, FontSize = 17, FontWeight = FontWeights.SemiBold, Foreground = TextMain, TextTrimming = TextTrimming.CharacterEllipsis };
            tRegion = new TextBlock { Text = s.Region, FontSize = 11.5, Foreground = TextMuted, TextTrimming = TextTrimming.CharacterEllipsis };
            titles.Children.Add(tCity); titles.Children.Add(tRegion);
            head.Children.Add(titles);
            main.Children.Add(head);

            // Météo actuelle
            var now = new Grid { Margin = new Thickness(0, 2, 0, 0) };
            now.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            now.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            now.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            tIcon = new TextBlock { Text = "…", FontFamily = Emoji, FontSize = 46, Foreground = Orange, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 8, 0) };
            tTemp = new TextBlock { Text = "--°", FontSize = 50, FontWeight = FontWeights.Light, Foreground = TextMain, VerticalAlignment = VerticalAlignment.Center };
            var right = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };
            tDesc = new TextBlock { Text = "Chargement…", FontSize = 14, FontWeight = FontWeights.SemiBold, Foreground = TextMain, TextWrapping = TextWrapping.Wrap };
            tFeels = new TextBlock { FontSize = 12, Foreground = TextMuted };
            tMinMax = new TextBlock { FontSize = 12, Foreground = TextMuted };
            right.Children.Add(tDesc); right.Children.Add(tFeels); right.Children.Add(tMinMax);
            Grid.SetColumn(tTemp, 1); Grid.SetColumn(right, 2);
            now.Children.Add(tIcon); now.Children.Add(tTemp); now.Children.Add(right);
            main.Children.Add(now);

            detailsPanel = new StackPanel();
            main.Children.Add(detailsPanel);

            // Petites infos
            var info = new UniformGrid { Columns = 4, Margin = new Thickness(0, 10, 0, 4) };
            tWind = InfoCell(info, "Vent");
            tRain = InfoCell(info, "Pluie");
            tHum = InfoCell(info, "Humidité");
            tUv = InfoCell(info, "UV");
            detailsPanel.Children.Add(info);

            // Prochaines heures
            hoursTitle = Section("PROCHAINES HEURES");
            detailsPanel.Children.Add(hoursTitle);
            hoursGrid = new UniformGrid { Columns = 8, Rows = 1 };
            detailsPanel.Children.Add(hoursGrid);

            // 5 jours
            detailsPanel.Children.Add(Section("5 JOURS"));
            daysPanel = new StackPanel();
            detailsPanel.Children.Add(daysPanel);

            tUpdated = new TextBlock { FontSize = 10.5, Foreground = TextMuted, Margin = new Thickness(0, 8, 0, 0), HorizontalAlignment = HorizontalAlignment.Center };
            main.Children.Add(tUpdated);

            // Panneau de recherche de ville
            searchOverlay = new Border { Background = BgCard, CornerRadius = new CornerRadius(12), Visibility = Visibility.Collapsed };
            var sp = new StackPanel();
            var sh = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
            var close = IconButton("✕", "Fermer", () => CloseSearch());
            DockPanel.SetDock(close, Dock.Right);
            sh.Children.Add(close);
            sh.Children.Add(new TextBlock { Text = "Changer de ville", FontSize = 15, FontWeight = FontWeights.SemiBold, Foreground = TextMain, VerticalAlignment = VerticalAlignment.Center });
            sp.Children.Add(sh);
            searchBox = new TextBox
            {
                FontSize = 14, Padding = new Thickness(8, 6, 8, 6), Background = BgCard2, Foreground = TextMain,
                BorderBrush = Orange, BorderThickness = new Thickness(1), CaretBrush = TextMain
            };
            searchBox.TextChanged += (o, e) => { searchDelay.Stop(); searchDelay.Start(); };
            searchBox.KeyDown += (o, e) => { if (e.Key == Key.Escape) CloseSearch(); };
            sp.Children.Add(searchBox);
            searchResults = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
            sp.Children.Add(searchResults);
            searchOverlay.Child = sp;
            root.Children.Add(searchOverlay);

            card.Child = root;

            // Menu clic droit sur le widget
            var cm = new ContextMenu();
            cm.Items.Add(MakeItem("Changer de ville", () => OpenSearch()));
            cm.Items.Add(MakeItem("Actualiser", async () => await Refresh()));
            cm.Items.Add(MakeItem("Mode compact / complet (double-clic)", () => { s.Compact = !s.Compact; ApplyCompact(); s.Save(); }));
            cm.Items.Add(MakeItem("Toujours au premier plan", () => SetTopmost(!s.Topmost)));
            cm.Items.Add(MakeItem("Masquer", () => Hide()));
            cm.Items.Add(new Separator());
            cm.Items.Add(MakeItem("Quitter", () => Quit()));
            card.ContextMenu = cm;
            return card;
        }

        TextBlock InfoCell(Panel parent, string label)
        {
            var b = new Border { Background = BgCard2, CornerRadius = new CornerRadius(10), Padding = new Thickness(6, 5, 6, 5), Margin = new Thickness(2) };
            var st = new StackPanel();
            st.Children.Add(new TextBlock { Text = label, FontSize = 10, Foreground = TextMuted });
            var v = new TextBlock { Text = "--", FontSize = 12.5, FontWeight = FontWeights.SemiBold, Foreground = TextMain, TextTrimming = TextTrimming.CharacterEllipsis };
            st.Children.Add(v);
            b.Child = st;
            parent.Children.Add(b);
            return v;
        }

        TextBlock Section(string t) => new TextBlock { Text = t, FontSize = 10.5, FontWeight = FontWeights.SemiBold, Foreground = TextMuted, Margin = new Thickness(0, 10, 0, 4) };

        Border IconButton(string glyph, string tip, Action click)
        {
            var b = new Border { Width = 28, Height = 28, CornerRadius = new CornerRadius(8), Background = Brushes.Transparent, Cursor = Cursors.Hand, ToolTip = tip, Margin = new Thickness(2, 0, 0, 0) };
            b.Child = new TextBlock { Text = glyph, FontFamily = Emoji, FontSize = 13, Foreground = TextMuted, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            b.MouseEnter += (o, e) => b.Background = BgCard2;
            b.MouseLeave += (o, e) => b.Background = Brushes.Transparent;
            b.MouseLeftButtonDown += (o, e) => e.Handled = true; // pas de déplacement
            b.MouseLeftButtonUp += (o, e) => { e.Handled = true; click(); };
            return b;
        }

        MenuItem MakeItem(string header, Action a)
        {
            var m = new MenuItem { Header = header };
            m.Click += (o, e) => a();
            return m;
        }

        void ApplyCompact()
        {
            detailsPanel.Visibility = s.Compact ? Visibility.Collapsed : Visibility.Visible;
            if (trayCompact != null) trayCompact.Checked = s.Compact;
        }

        void SetTopmost(bool on)
        {
            s.Topmost = on; Topmost = on; s.Save();
            ((TextBlock)pinBtn.Child).Foreground = on ? Orange : TextMuted;
            if (trayTop != null) trayTop.Checked = on;
        }

        // ---------- Icône près de l'horloge ----------
        void BuildTray()
        {
            System.Drawing.Icon ico = null;
            try { ico = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath); } catch { }
            tray = new WF.NotifyIcon { Text = "Phoenix Pro Météo", Icon = ico ?? System.Drawing.SystemIcons.Application, Visible = true };
            var menu = new WF.ContextMenuStrip();
            menu.Items.Add("Afficher / masquer", null, (o, e) => ToggleVisible());
            menu.Items.Add("Changer de ville", null, (o, e) => { ShowWidget(); OpenSearch(); });
            menu.Items.Add("Actualiser", null, async (o, e) => await Refresh());
            menu.Items.Add(new WF.ToolStripSeparator());
            trayTop = new WF.ToolStripMenuItem("Toujours au premier plan") { Checked = s.Topmost };
            trayTop.Click += (o, e) => SetTopmost(!s.Topmost);
            trayCompact = new WF.ToolStripMenuItem("Mode compact") { Checked = s.Compact };
            trayCompact.Click += (o, e) => { s.Compact = !s.Compact; ApplyCompact(); s.Save(); };
            trayAuto = new WF.ToolStripMenuItem("Lancer au démarrage de Windows") { Checked = IsAutoStart() };
            trayAuto.Click += (o, e) => { SetAutoStart(!IsAutoStart()); trayAuto.Checked = IsAutoStart(); };
            menu.Items.Add(trayTop); menu.Items.Add(trayCompact); menu.Items.Add(trayAuto);
            menu.Items.Add(new WF.ToolStripMenuItem("Remettre en haut à droite", null, (o, e) => { s.Left = null; s.Top = null; PlaceWindow(); s.Save(); ShowWidget(); }));
            menu.Items.Add(new WF.ToolStripSeparator());
            menu.Items.Add("Quitter", null, (o, e) => Quit());
            tray.ContextMenuStrip = menu;
            tray.MouseClick += (o, e) => { if (e.Button == WF.MouseButtons.Left) ToggleVisible(); };
            Dispatcher.InvokeAsync(() => SetTopmost(s.Topmost), DispatcherPriority.Loaded);
        }

        void ToggleVisible() { if (IsVisible) Hide(); else ShowWidget(); }
        void ShowWidget() { Show(); Activate(); }

        void Quit()
        {
            tray.Visible = false; tray.Dispose();
            Application.Current.Shutdown();
        }

        static bool IsAutoStart()
        {
            try { using var k = Registry.CurrentUser.OpenSubKey(RunKey); return k?.GetValue(RunName) != null; } catch { return false; }
        }

        static void SetAutoStart(bool on)
        {
            try
            {
                using var k = Registry.CurrentUser.OpenSubKey(RunKey, true) ?? Registry.CurrentUser.CreateSubKey(RunKey);
                if (on) k.SetValue(RunName, "\"" + Environment.ProcessPath + "\"");
                else k.DeleteValue(RunName, false);
            }
            catch { }
        }

        // ---------- Recherche de ville ----------
        void OpenSearch()
        {
            if (s.Compact) { s.Compact = false; ApplyCompact(); }
            searchOverlay.Visibility = Visibility.Visible;
            searchBox.Text = "";
            searchResults.Children.Clear();
            searchResults.Children.Add(Hint("Tape le nom d'une ville…"));
            Activate();
            searchBox.Focus();
        }

        void CloseSearch() => searchOverlay.Visibility = Visibility.Collapsed;

        TextBlock Hint(string t) => new TextBlock { Text = t, Foreground = TextMuted, FontSize = 12, Margin = new Thickness(2, 4, 0, 0), TextWrapping = TextWrapping.Wrap };

        async Task Search(string q)
        {
            q = (q ?? "").Trim();
            searchResults.Children.Clear();
            if (q.Length < 2) { searchResults.Children.Add(Hint("Tape le nom d'une ville…")); return; }
            try
            {
                var json = await http.GetStringAsync("https://geocoding-api.open-meteo.com/v1/search?count=7&language=fr&format=json&name=" + Uri.EscapeDataString(q));
                using var doc = JsonDocument.Parse(json);
                searchResults.Children.Clear();
                if (!doc.RootElement.TryGetProperty("results", out var arr) || arr.GetArrayLength() == 0)
                { searchResults.Children.Add(Hint("Aucune ville trouvée.")); return; }
                foreach (var p in arr.EnumerateArray())
                {
                    string name = Str(p, "name");
                    var parts = new List<string>();
                    foreach (var k in new[] { "admin2", "admin1", "country" }) { var v = Str(p, k); if (v != "" && !parts.Contains(v)) parts.Add(v); }
                    string region = string.Join(", ", parts);
                    double lat = p.GetProperty("latitude").GetDouble(), lon = p.GetProperty("longitude").GetDouble();
                    string admin1 = Str(p, "admin1"), country = Str(p, "country");

                    var row = new Border { Padding = new Thickness(8, 6, 8, 6), CornerRadius = new CornerRadius(8), Cursor = Cursors.Hand, Background = Brushes.Transparent };
                    var st = new StackPanel();
                    st.Children.Add(new TextBlock { Text = name, Foreground = TextMain, FontSize = 13.5, FontWeight = FontWeights.SemiBold });
                    st.Children.Add(new TextBlock { Text = region, Foreground = TextMuted, FontSize = 11, TextTrimming = TextTrimming.CharacterEllipsis });
                    row.Child = st;
                    row.MouseEnter += (o, e) => row.Background = BgCard2;
                    row.MouseLeave += (o, e) => row.Background = Brushes.Transparent;
                    row.MouseLeftButtonDown += (o, e) => e.Handled = true;
                    row.MouseLeftButtonUp += async (o, e) =>
                    {
                        e.Handled = true;
                        s.Name = name; s.Lat = lat; s.Lon = lon;
                        s.Region = string.Join(", ", new[] { admin1, country }).Trim(',', ' ');
                        s.Save();
                        tCity.Text = s.Name; tRegion.Text = s.Region;
                        CloseSearch();
                        await Refresh();
                    };
                    searchResults.Children.Add(row);
                }
            }
            catch
            {
                searchResults.Children.Clear();
                searchResults.Children.Add(Hint("Recherche impossible : vérifie la connexion Internet."));
            }
        }

        // ---------- Météo ----------
        async Task Refresh()
        {
            tUpdated.Text = "Actualisation…";
            try
            {
                string url = string.Format(CultureInfo.InvariantCulture,
                    "https://api.open-meteo.com/v1/forecast?latitude={0}&longitude={1}" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,uv_index_max,wind_speed_10m_max" +
                    "&timezone=auto&forecast_days=6&wind_speed_unit=kmh", s.Lat, s.Lon);
                var json = await http.GetStringAsync(url);
                using var doc = JsonDocument.Parse(json);
                Render(doc.RootElement);
                timer.Interval = TimeSpan.FromMinutes(30);
            }
            catch
            {
                tUpdated.Text = "Hors ligne — nouvel essai dans 5 min";
                timer.Interval = TimeSpan.FromMinutes(5);
            }
        }

        void Render(JsonElement r)
        {
            var c = r.GetProperty("current");
            var d = r.GetProperty("daily");
            var h = r.GetProperty("hourly");
            int code = I(c, "weather_code");
            bool isDay = I(c, "is_day") == 1;
            var w = Wmo(code, isDay);

            tCity.Text = s.Name; tRegion.Text = s.Region;
            tIcon.Text = w.icon; tIcon.Foreground = IconColor(code, isDay);
            tTemp.Text = Deg(D(c, "temperature_2m"));
            tDesc.Text = w.text;
            tFeels.Text = "Ressenti " + Deg(D(c, "apparent_temperature"));
            tMinMax.Text = "Min " + Deg(At(d, "temperature_2m_min", 0)) + "  ·  Max " + Deg(At(d, "temperature_2m_max", 0));
            tWind.Text = R(D(c, "wind_speed_10m")) + " km/h " + Dir(D(c, "wind_direction_10m"));
            tWind.ToolTip = "Rafales : " + R(D(c, "wind_gusts_10m")) + " km/h";
            tRain.Text = R(At(d, "precipitation_probability_max", 0)) + " %";
            tRain.ToolTip = At(d, "precipitation_sum", 0).ToString("0.0", Fr) + " mm prévus aujourd'hui";
            tHum.Text = R(D(c, "relative_humidity_2m")) + " %";
            double uv = At(d, "uv_index_max", 0);
            tUv.Text = R(uv) + " · " + UvLabel(uv);

            // heures : à partir de l'heure actuelle
            string nowHour = c.GetProperty("time").GetString().Substring(0, 13);
            var times = h.GetProperty("time");
            int start = 0;
            for (int i = 0; i < times.GetArrayLength(); i++) if (times[i].GetString().StartsWith(nowHour)) { start = i; break; }
            hoursGrid.Children.Clear();
            for (int k = 0; k < 8 && start + k < times.GetArrayLength(); k++)
            {
                int i = start + k;
                int hc = (int)At(h, "weather_code", i);
                bool hd = At(h, "is_day", i) == 1;
                var col = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
                col.Children.Add(Center(k == 0 ? "Maint." : times[i].GetString().Substring(11, 2) + "h", 10.5, k == 0 ? Orange : TextMuted));
                var ic = Center(Wmo(hc, hd).icon, 17, IconColor(hc, hd)); ic.FontFamily = Emoji; ic.Margin = new Thickness(0, 2, 0, 2);
                ic.ToolTip = Wmo(hc, hd).text;
                col.Children.Add(ic);
                col.Children.Add(Center(Deg(At(h, "temperature_2m", i)), 12.5, TextMain, true));
                double pp = At(h, "precipitation_probability", i);
                col.Children.Add(Center(pp >= 10 ? R(pp) + "%" : " ", 10, Blue));
                hoursGrid.Children.Add(col);
            }

            // 5 jours
            daysPanel.Children.Clear();
            var dt = d.GetProperty("time");
            int n = Math.Min(5, dt.GetArrayLength());
            double gMin = double.MaxValue, gMax = double.MinValue;
            for (int i = 0; i < n; i++) { gMin = Math.Min(gMin, At(d, "temperature_2m_min", i)); gMax = Math.Max(gMax, At(d, "temperature_2m_max", i)); }
            for (int i = 0; i < n; i++)
            {
                var date = DateTime.ParseExact(dt[i].GetString(), "yyyy-MM-dd", CultureInfo.InvariantCulture);
                string name = i == 0 ? "Auj." : i == 1 ? "Demain" : Fr.TextInfo.ToTitleCase(date.ToString("ddd d", Fr));
                int dc = (int)At(d, "weather_code", i);
                double mn = At(d, "temperature_2m_min", i), mx = At(d, "temperature_2m_max", i), pr = At(d, "precipitation_probability_max", i);

                var g = new Grid { Margin = new Thickness(0, 3, 0, 3) };
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(66) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(30) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(30) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(32) });
                g.ToolTip = Wmo(dc, true).text + " — vent jusqu'à " + R(At(d, "wind_speed_10m_max", i)) + " km/h, UV " + R(At(d, "uv_index_max", i));

                var tn = new TextBlock { Text = name, FontSize = 12.5, FontWeight = FontWeights.SemiBold, Foreground = TextMain, VerticalAlignment = VerticalAlignment.Center };
                var ti = new TextBlock { Text = Wmo(dc, true).icon, FontFamily = Emoji, FontSize = 16, Foreground = IconColor(dc, true), VerticalAlignment = VerticalAlignment.Center };
                var tp = new TextBlock { Text = pr >= 20 ? R(pr) + "%" : "", FontSize = 11, Foreground = Blue, VerticalAlignment = VerticalAlignment.Center };
                var tmn = new TextBlock { Text = Deg(mn), FontSize = 12.5, Foreground = TextMuted, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 0, 6, 0) };
                var tmx = new TextBlock { Text = Deg(mx), FontSize = 12.5, FontWeight = FontWeights.SemiBold, Foreground = TextMain, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Right };

                // barre de températures
                double span = Math.Max(gMax - gMin, 1);
                var bar = new Grid { Height = 5, VerticalAlignment = VerticalAlignment.Center };
                bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(mn - gMin, 0.001) / span, GridUnitType.Star) });
                bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(mx - mn, span * 0.05) / span, GridUnitType.Star) });
                bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(gMax - mx, 0.001) / span, GridUnitType.Star) });
                var track = new Border { Background = Line, CornerRadius = new CornerRadius(3) };
                Grid.SetColumnSpan(track, 3);
                var fill = new Border
                {
                    CornerRadius = new CornerRadius(3),
                    Background = new LinearGradientBrush(((SolidColorBrush)Blue).Color, ((SolidColorBrush)Orange).Color, 0)
                };
                Grid.SetColumn(fill, 1);
                bar.Children.Add(track); bar.Children.Add(fill);

                Grid.SetColumn(ti, 1); Grid.SetColumn(tp, 2); Grid.SetColumn(tmn, 3); Grid.SetColumn(bar, 4); Grid.SetColumn(tmx, 5);
                g.Children.Add(tn); g.Children.Add(ti); g.Children.Add(tp); g.Children.Add(tmn); g.Children.Add(bar); g.Children.Add(tmx);
                daysPanel.Children.Add(g);
            }

            tUpdated.Text = "Mis à jour à " + DateTime.Now.ToString("HH'h'mm") + " · Open-Meteo";
            string tip = s.Name + " : " + Deg(D(c, "temperature_2m")) + " · " + w.text;
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
        }

        static TextBlock Center(string t, double size, Brush fg, bool bold = false) =>
            new TextBlock { Text = t, FontSize = size, Foreground = fg, HorizontalAlignment = HorizontalAlignment.Center, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal };

        // ---------- Codes météo ----------
        static (string text, string icon) Wmo(int code, bool day)
        {
            string sun = day ? "☀" : "🌙";
            switch (code)
            {
                case 0: return ("Ciel dégagé", sun);
                case 1: return ("Plutôt dégagé", day ? "🌤" : "🌙");
                case 2: return ("Partiellement nuageux", day ? "⛅" : "☁");
                case 3: return ("Couvert", "☁");
                case 45: return ("Brouillard", "🌫");
                case 48: return ("Brouillard givrant", "🌫");
                case 51: return ("Bruine légère", "🌦");
                case 53: return ("Bruine", "🌦");
                case 55: return ("Bruine forte", "🌧");
                case 56: case 57: return ("Bruine verglaçante", "🌧");
                case 61: return ("Pluie faible", "🌦");
                case 63: return ("Pluie", "🌧");
                case 65: return ("Forte pluie", "🌧");
                case 66: case 67: return ("Pluie verglaçante", "🌧");
                case 71: return ("Neige faible", "🌨");
                case 73: return ("Neige", "🌨");
                case 75: return ("Forte neige", "❄");
                case 77: return ("Grains de neige", "🌨");
                case 80: return ("Averses faibles", "🌦");
                case 81: return ("Averses", "🌧");
                case 82: return ("Violentes averses", "⛈");
                case 85: case 86: return ("Averses de neige", "🌨");
                case 95: return ("Orage", "⛈");
                case 96: case 99: return ("Orage avec grêle", "⛈");
                default: return ("—", "☁");
            }
        }

        static Brush IconColor(int code, bool day)
        {
            if (code <= 1) return day ? Orange : Hex("#FFD8E3FF");
            if (code <= 3 || code == 45 || code == 48) return Hex("#FFC9CED3");
            if (code >= 95) return Hex("#FFB48CF0");
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Brushes.White;
            return Blue;
        }

        static string UvLabel(double u) => u < 3 ? "faible" : u < 6 ? "modéré" : u < 8 ? "élevé" : u < 11 ? "très élevé" : "extrême";
        static string Dir(double deg) => new[] { "N", "NE", "E", "SE", "S", "SO", "O", "NO" }[(int)Math.Round(deg / 45.0) % 8];
        static string R(double v) => ((int)Math.Round(v)).ToString();
        static string Deg(double v) => R(v) + "°";
        static string Str(JsonElement e, string k) => e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : "";
        static double D(JsonElement e, string k) => e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0;
        static int I(JsonElement e, string k) => (int)D(e, k);
        static double At(JsonElement e, string k, int i)
        {
            if (!e.TryGetProperty(k, out var a) || i >= a.GetArrayLength()) return 0;
            var v = a[i];
            return v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0;
        }
        static SolidColorBrush Hex(string h) { var b = (SolidColorBrush)new BrushConverter().ConvertFromString(h); b.Freeze(); return b; }
    }
}
