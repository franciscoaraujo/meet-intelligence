import React, { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard,
  Video,
  Calendar,
  ClipboardList,
  Sliders,
  Settings,
  LogOut,
  Search,
  Building2,
  User as UserIcon,
  Sparkles,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';

export const AppLayout: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [quickSearch, setQuickSearch] = useState('');

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (quickSearch.trim()) {
      navigate('/meetings');
    }
  };

  const navItems = [
    { label: 'Visão Geral', path: '/', icon: LayoutDashboard },
    { label: 'Agenda Sincronizada', path: '/calendar', icon: Calendar },
    { label: 'Reuniões Google Meet', path: '/meetings', icon: Video },
    { label: 'Atas e Relatórios', path: '/summaries', icon: ClipboardList },
    { label: 'Perfis de Análise', path: '/ai-profiles', icon: Sliders },
    { label: 'Configurações & Planos', path: '/settings', icon: Settings },
    { label: 'Por que Meet Intelligence?', path: '/landing', icon: Sparkles },
  ];

  return (
    <div className="flex flex-col h-screen bg-[#f8fafd] text-[#1f1f1f] overflow-hidden">
      {/* Top App Bar — Padrão Google Workspace Material Design 3 */}
      <header className="h-16 px-6 flex items-center justify-between shrink-0 bg-[#f8fafd]">
        {/* Brand */}
        <div className="flex items-center gap-3 w-60 shrink-0">
          <div className="w-10 h-10 rounded-xl bg-[#0b57d0] flex items-center justify-center text-white shadow-xs">
            <Video className="w-5 h-5" />
          </div>
          <div>
            <span className="font-semibold text-[17px] tracking-tight text-[#1f1f1f] block leading-tight">
              Meet Intelligence
            </span>
            <span className="text-[11px] text-[#444746] font-medium block">
              Workspace Enterprise
            </span>
          </div>
        </div>

        {/* Barra de Pesquisa Global Material 3 */}
        <form onSubmit={handleSearchSubmit} className="hidden md:flex flex-1 max-w-xl mx-6">
          <div className="relative w-full">
            <Search className="w-4 h-4 text-[#444746] absolute left-4 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={quickSearch}
              onChange={(e) => setQuickSearch(e.target.value)}
              placeholder="Pesquisar em reuniões agendadas, atas e transcrições..."
              className="w-full pl-11 pr-4 py-2.5 bg-[#edf2fc] hover:bg-[#e4ebf7] focus:bg-white border border-transparent focus:border-[#0b57d0] rounded-full text-sm text-[#1f1f1f] placeholder-[#444746] focus:outline-none focus:shadow-sm transition-all"
            />
          </div>
        </form>

        {/* Ações e Perfil do Usuário */}
        <div className="flex items-center gap-3">
          <div className="hidden sm:flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-white border border-[#c4c7c5] text-xs font-medium text-[#1f1f1f]">
            <Building2 className="w-3.5 h-3.5 text-[#0b57d0]" />
            <span className="max-w-[160px] truncate">{user?.organizationName || 'Organização'}</span>
          </div>

          <div className="flex items-center gap-2.5 pl-2 border-l border-[#e0e3e7]">
            {user?.avatarUrl ? (
              <img
                src={user.avatarUrl}
                alt="avatar"
                referrerPolicy="no-referrer"
                className="w-8 h-8 rounded-full border border-[#c4c7c5]"
              />
            ) : (
              <div className="w-8 h-8 rounded-full bg-[#d3e3fd] text-[#041e49] flex items-center justify-center font-semibold text-xs">
                {user?.fullName ? user.fullName.charAt(0).toUpperCase() : <UserIcon className="w-4 h-4" />}
              </div>
            )}
            <div className="hidden lg:block max-w-[150px] truncate">
              <p className="text-xs font-medium text-[#1f1f1f] truncate">{user?.fullName || 'Usuário'}</p>
              <p className="text-[11px] text-[#444746] truncate">{user?.email}</p>
            </div>
            <button
              onClick={handleLogout}
              title="Encerrar sessão"
              className="p-2 rounded-full text-[#444746] hover:text-[#b3261e] hover:bg-[#f9dedc]/60 transition-colors cursor-pointer"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* Corpo com Navigation Drawer + Surface Container */}
      <div className="flex flex-1 min-h-0">
        {/* Material 3 Navigation Drawer */}
        <aside className="w-64 px-3 py-2 flex flex-col justify-between shrink-0 bg-[#f8fafd]">
          <nav className="space-y-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.path === '/'}
                  className={({ isActive }) =>
                    `flex items-center gap-3.5 px-4 py-2.5 text-[13.5px] rounded-full transition-colors ${
                      isActive
                        ? 'bg-[#d3e3fd] text-[#041e49] font-semibold'
                        : 'text-[#444746] hover:bg-[#edf2fc] hover:text-[#1f1f1f] font-medium'
                    }`
                  }
                >
                  <Icon className="w-[18px] h-[18px] shrink-0" />
                  <span className="truncate">{item.label}</span>
                </NavLink>
              );
            })}
          </nav>

          {/* Rodapé Informativo */}
          <div className="p-4 mx-1 mb-2 rounded-2xl bg-[#edf2fc] border border-[#d3e3fd]">
            <p className="text-xs font-semibold text-[#041e49]">Google Meet Sync</p>
            <p className="text-[11px] text-[#444746] mt-0.5 leading-relaxed">
              Sincronizado com eventos reais agendados no Google Calendar.
            </p>
          </div>
        </aside>

        {/* Main Surface Container — Padrão Google Workspace */}
        <main className="flex-1 bg-white rounded-tl-[24px] border-t border-l border-[#e0e3e7] overflow-y-auto mr-3 mb-3 rounded-tr-[16px] rounded-b-[16px] border-r border-b shadow-2xs">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
