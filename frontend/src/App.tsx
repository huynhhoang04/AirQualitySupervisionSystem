import { Navigate, Route, Routes } from "react-router-dom";

import { DevicePage, SettingsPage } from "./pages/DevicePage";
import { OverviewPage } from "./pages/OverviewPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<OverviewPage />} />
      <Route path="/device/:deviceId" element={<DevicePage />} />
      <Route path="/device/:deviceId/settings" element={<SettingsPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
